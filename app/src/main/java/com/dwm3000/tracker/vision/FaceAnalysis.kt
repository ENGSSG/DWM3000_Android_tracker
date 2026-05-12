package com.dwm3000.tracker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class FaceAnalysis(
    context: Context,
    private val onResult: (Bitmap, List<DetectedFace>, FaceDetectionStats) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val primaryDetector: FaceDetectorBackend
    private val fallbackDetector: FaceDetectorBackend
    private var lastFrameNs = 0L
    private var smoothedFps = 0f
    private var lastConversionMs = 0f
    private var lastInferenceMs = 0f
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
            val startNs = System.nanoTime()
            val detectedFaces = detectFaces(bitmap)
            lastInferenceMs = (System.nanoTime() - startNs) / 1_000_000f
            lastTotalAnalysisMs = (System.nanoTime() - totalStartNs) / 1_000_000f

            onResult(
                bitmap,
                detectedFaces,
                FaceDetectionStats(
                    detectorName = activeDetectorName,
                    frameFps = smoothedFps,
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height,
                    conversionMs = lastConversionMs,
                    inferenceMs = lastInferenceMs,
                    totalAnalysisMs = lastTotalAnalysisMs,
                    faceCount = detectedFaces.size,
                    faceSource = "CNN"
                )
            )
            resultBitmap = null
        } catch (e: Exception) {
            resultBitmap?.recycle()
            Log.e(TAG, "Face analysis failed", e)
        } finally {
            if (!imageClosed) image.close()
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
        primaryDetector.close()
        if (primaryDetector !== fallbackDetector) fallbackDetector.close()
    }

    companion object {
        private const val TAG = "FaceAnalysis"
    }
}
