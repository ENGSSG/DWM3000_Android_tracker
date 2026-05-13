package com.dwm3000.tracker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.dwm3000.tracker.tracking.CameraImuMotionBuffer
import com.dwm3000.tracker.tracking.FacePredictionTracker
import com.dwm3000.tracker.tracking.TrackingSignalStore
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class FaceAnalysis(
    context: Context,
    private val motionBuffer: CameraImuMotionBuffer,
    trackingSignalStore: TrackingSignalStore,
    private val detectorIntervalMs: Long = DEFAULT_DETECTOR_INTERVAL_MS,
    private val onResult: (Bitmap, List<DetectedFace>, FaceDetectionStats) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val primaryDetector: FaceDetectorBackend
    private val fallbackDetector: FaceDetectorBackend
    private val tracker = FacePredictionTracker(motionBuffer, trackingSignalStore)
    private val cnnExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cnnInFlight = AtomicBoolean(false)
    private val frameBuffer = ArrayDeque<BufferedFrame>()
    private val pendingCnnResults = ArrayDeque<CnnDetectionResult>()
    private val pendingCnnLock = Any()
    @Volatile private var closed = false
    @Volatile private var lastInferenceMs = 0f

    private var lastFrameNs = 0L
    private var lastCnnStartFrameNs = 0L
    private var smoothedFps = 0f
    private var lastConversionMs = 0f
    private var lastTotalAnalysisMs = 0f
    private var activeDetectorName = ""

    init {
        val appContext = context.applicationContext
        fallbackDetector = MediaPipeFaceDetector(
            appContext,
            modelAssetPath = MediaPipeFaceDetector.SHORT_RANGE_MODEL_ASSET,
            enableFallback = false
        )
        primaryDetector = try {
            YuNetFaceDetector(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "YuNet unavailable. Falling back to MediaPipe.", e)
            fallbackDetector
        }
        activeDetectorName = primaryDetector.name
    }

    override fun analyze(image: ImageProxy) {
        val totalStartNs = System.nanoTime()
        val frameNs = image.imageInfo.timestamp
        var resultBitmap: Bitmap? = null
        var imageClosed = false
        try {
            updateFps(frameNs)

            val conversionStartNs = System.nanoTime()
            val bitmap = image.toUprightRgbaBitmap()
            lastConversionMs = (System.nanoTime() - conversionStartNs) / 1_000_000f
            image.close()
            imageClosed = true
            resultBitmap = bitmap
            maybeStartCnn(bitmap, frameNs)
            bufferFrame(BufferedFrame(frameNs, bitmap))
            resultBitmap = null
            renderBufferedFrame(frameNs, totalStartNs)
        } catch (e: Exception) {
            resultBitmap?.recycle()
            Log.e(TAG, "Face analysis failed", e)
        } finally {
            if (!imageClosed) image.close()
        }
    }

    fun setCameraFov(horizontalDeg: Float, verticalDeg: Float) {
        tracker.setCameraFov(horizontalDeg, verticalDeg)
    }

    private fun maybeStartCnn(bitmap: Bitmap, frameNs: Long) {
        if (closed) return
        if (lastCnnStartFrameNs > 0L &&
            frameNs - lastCnnStartFrameNs < detectorIntervalMs * 1_000_000L
        ) {
            return
        }
        if (!cnnInFlight.compareAndSet(false, true)) return

        lastCnnStartFrameNs = frameNs
        val cnnBitmap = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            cnnInFlight.set(false)
            Log.e(TAG, "CNN snapshot copy failed", e)
            return
        }

        try {
            cnnExecutor.execute {
                try {
                    val startNs = System.nanoTime()
                    val detectedFaces = detectFaces(cnnBitmap)
                    lastInferenceMs = (System.nanoTime() - startNs) / 1_000_000f
                    if (!closed) {
                        enqueueCnnResult(
                            CnnDetectionResult(
                                frameTimestampNs = frameNs,
                                imageWidth = cnnBitmap.width,
                                imageHeight = cnnBitmap.height,
                                faces = detectedFaces
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Async face detection failed", e)
                } finally {
                    cnnBitmap.recycle()
                    cnnInFlight.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            cnnBitmap.recycle()
            cnnInFlight.set(false)
        }
    }

    private fun bufferFrame(frame: BufferedFrame) {
        frameBuffer.addLast(frame)
        pruneFrameBuffer(frame.timestampNs)
    }

    private fun renderBufferedFrame(latestFrameNs: Long, totalStartNs: Long) {
        val targetNs = latestFrameNs - DISPLAY_DELAY_NS
        val renderTimestampNs = latestEligibleFrameTimestamp(targetNs) ?: return

        applyCnnResultsThrough(renderTimestampNs)
        if (!tracker.hasValidStateForFrame(renderTimestampNs)) {
            pruneFrameBuffer(latestFrameNs)
            return
        }

        var frameToRender: BufferedFrame? = null
        while (frameBuffer.isNotEmpty() && frameBuffer.first.timestampNs <= renderTimestampNs) {
            val candidate = frameBuffer.removeFirst()
            frameToRender?.bitmap?.recycle()
            frameToRender = candidate
        }

        val frame = frameToRender ?: return
        val prediction = tracker.predictForFrame(
            frame.timestampNs,
            frame.bitmap.width,
            frame.bitmap.height
        )
        if (prediction.faces.isEmpty()) {
            frame.bitmap.recycle()
            return
        }

        lastTotalAnalysisMs = (System.nanoTime() - totalStartNs) / 1_000_000f
        onResult(
            frame.bitmap,
            prediction.faces,
            FaceDetectionStats(
                detectorName = activeDetectorName,
                frameFps = smoothedFps,
                frameWidth = frame.bitmap.width,
                frameHeight = frame.bitmap.height,
                conversionMs = lastConversionMs,
                inferenceMs = lastInferenceMs,
                totalAnalysisMs = lastTotalAnalysisMs,
                faceCount = prediction.faces.size,
                faceSource = prediction.faceSource,
                detectorIntervalMs = detectorIntervalMs,
                predictionAgeMs = prediction.predictionAgeMs,
                imuShiftX = prediction.imuShiftX,
                imuShiftY = prediction.imuShiftY,
                uwbShiftX = prediction.uwbShiftX,
                uwbShiftY = prediction.uwbShiftY
            )
        )
    }

    private fun latestEligibleFrameTimestamp(targetNs: Long): Long? {
        var latest: Long? = null
        frameBuffer.forEach { frame ->
            if (frame.timestampNs <= targetNs) {
                latest = frame.timestampNs
            }
        }
        return latest
    }

    private fun pruneFrameBuffer(latestFrameNs: Long) {
        val minTimestampNs = latestFrameNs - MAX_DISPLAY_BUFFER_NS
        while (frameBuffer.size > MAX_BUFFERED_FRAMES ||
            (frameBuffer.isNotEmpty() && frameBuffer.first.timestampNs < minTimestampNs)
        ) {
            frameBuffer.removeFirst().bitmap.recycle()
        }
    }

    private fun enqueueCnnResult(result: CnnDetectionResult) {
        synchronized(pendingCnnLock) {
            pendingCnnResults.addLast(result)
            while (pendingCnnResults.size > MAX_PENDING_CNN_RESULTS) {
                pendingCnnResults.removeFirst()
            }
        }
    }

    private fun applyCnnResultsThrough(frameTimestampNs: Long) {
        val results = mutableListOf<CnnDetectionResult>()
        synchronized(pendingCnnLock) {
            while (pendingCnnResults.isNotEmpty() &&
                pendingCnnResults.first.frameTimestampNs <= frameTimestampNs
            ) {
                results += pendingCnnResults.removeFirst()
            }
        }
        results.forEach { result ->
            tracker.onCnnResult(
                result.faces,
                result.frameTimestampNs,
                result.imageWidth,
                result.imageHeight
            )
        }
    }

    private fun updateFps(frameNs: Long) {
        if (lastFrameNs > 0L) {
            val instant = 1_000_000_000f / max(1L, frameNs - lastFrameNs)
            smoothedFps = if (smoothedFps == 0f) instant else smoothedFps * 0.85f + instant * 0.15f
        }
        lastFrameNs = frameNs
    }

    private fun detectFaces(bitmap: Bitmap): List<DetectedFace> {
        activeDetectorName = primaryDetector.name
        return primaryDetector.detect(bitmap)
    }

    private fun ImageProxy.toUprightRgbaBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val plane = planes[0]
        val source = plane.buffer
        source.rewind()

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val packedRowBytes = width * 4

        if (pixelStride == 4 && rowStride == packedRowBytes) {
            bitmap.copyPixelsFromBuffer(source)
        } else {
            val packed = ByteBuffer.allocateDirect(width * height * 4)
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                source.position(y * rowStride)
                source.get(row, 0, min(rowStride, source.remaining()))
                packed.put(row, 0, packedRowBytes)
            }
            packed.rewind()
            bitmap.copyPixelsFromBuffer(packed)
        }

        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return bitmap

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        return rotated
    }

    override fun close() {
        closed = true
        tracker.reset()
        clearFrameBuffer()
        synchronized(pendingCnnLock) {
            pendingCnnResults.clear()
        }
        cnnExecutor.shutdown()
        try {
            if (!cnnExecutor.awaitTermination(300, TimeUnit.MILLISECONDS)) {
                cnnExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            cnnExecutor.shutdownNow()
        }
        primaryDetector.close()
        if (primaryDetector !== fallbackDetector) fallbackDetector.close()
    }

    private fun clearFrameBuffer() {
        while (frameBuffer.isNotEmpty()) {
            frameBuffer.removeFirst().bitmap.recycle()
        }
    }

    private data class BufferedFrame(
        val timestampNs: Long,
        val bitmap: Bitmap
    )

    private data class CnnDetectionResult(
        val frameTimestampNs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val faces: List<DetectedFace>
    )

    companion object {
        private const val TAG = "FaceAnalysis"
        private const val DEFAULT_DETECTOR_INTERVAL_MS = 200L
        private const val DISPLAY_DELAY_NS = 120_000_000L
        private const val MAX_DISPLAY_BUFFER_NS = 600_000_000L
        private const val MAX_BUFFERED_FRAMES = 12
        private const val MAX_PENDING_CNN_RESULTS = 4
    }
}
