package com.dwm3000.tracker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max

class FaceAnalysis(
    context: Context,
    private val motionSensor: GyroImageMotionSensor,
    private val detectorIntervalMs: Long = 200L,
    private val onResult: (Bitmap, List<DetectedFace>, FaceDetectionStats) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = YuNetFaceDetector(context.applicationContext)
    private var lastDetectionNs = 0L
    private var lastFrameNs = 0L
    private var smoothedFps = 0f
    private var lastInferenceMs = 0f
    private var lastFaces: List<DetectedFace> = emptyList()

    override fun analyze(image: ImageProxy) {
        val frameNs = image.imageInfo.timestamp
        try {
            updateFps(frameNs)

            val bitmap = image.toUprightBitmap()
            val shouldDetect = frameNs - lastDetectionNs >= detectorIntervalMs * 1_000_000L
            val imuShift = motionSensor.consumePixelShiftUntil(frameNs, bitmap.width, bitmap.height)

            if (!shouldDetect && lastFaces.isNotEmpty()) {
                lastFaces = lastFaces.translate(imuShift.x, imuShift.y, bitmap.width, bitmap.height)
            }

            if (shouldDetect) {
                val startNs = System.nanoTime()
                lastFaces = detector.detect(bitmap)
                lastInferenceMs = (System.nanoTime() - startNs) / 1_000_000f
                lastDetectionNs = frameNs
            }

            onResult(
                bitmap,
                lastFaces,
                FaceDetectionStats(
                    detectorName = detector.name,
                    frameFps = smoothedFps,
                    inferenceMs = lastInferenceMs,
                    faceCount = lastFaces.size,
                    detectorIntervalMs = detectorIntervalMs,
                    imuShiftX = imuShift.x,
                    imuShiftY = imuShift.y
                )
            )
        } catch (_: Exception) {
            // Keep the camera stream alive even if a single frame conversion fails.
        } finally {
            image.close()
        }
    }

    private fun updateFps(frameNs: Long) {
        if (lastFrameNs > 0L) {
            val instant = 1_000_000_000f / max(1L, frameNs - lastFrameNs)
            smoothedFps = if (smoothedFps == 0f) instant else smoothedFps * 0.85f + instant * 0.15f
        }
        lastFrameNs = frameNs
    }

    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val nv21 = toNv21()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val jpeg = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 75, jpeg)
        val bitmap = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size())
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return bitmap

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        return rotated
    }

    private fun ImageProxy.toNv21(): ByteArray {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val out = ByteArray(width * height * 3 / 2)

        var outputOffset = 0
        for (row in 0 until height) {
            val inputOffset = row * yPlane.rowStride
            for (col in 0 until width) {
                out[outputOffset++] = yPlane.buffer.get(inputOffset + col)
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vuIndex = width * height + row * width + col * 2
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                out[vuIndex] = vBuffer.get(vIndex)
                out[vuIndex + 1] = uBuffer.get(uIndex)
            }
        }

        return out
    }

    private fun List<DetectedFace>.translate(dx: Float, dy: Float, imageWidth: Int, imageHeight: Int): List<DetectedFace> {
        if (kotlin.math.abs(dx) < 0.1f && kotlin.math.abs(dy) < 0.1f) return this
        return map { face ->
            val shiftedBox = face.bbox.translateClamped(dx, dy, imageWidth, imageHeight)
            val shiftedLandmarks = face.landmarks.map { (x, y) ->
                (x + dx).coerceIn(0f, imageWidth.toFloat()) to (y + dy).coerceIn(0f, imageHeight.toFloat())
            }
            face.copy(bbox = shiftedBox, landmarks = shiftedLandmarks)
        }
    }

    private fun RectF.translateClamped(dx: Float, dy: Float, imageWidth: Int, imageHeight: Int): RectF {
        val width = width()
        val height = height()
        val left = (left + dx).coerceIn(0f, (imageWidth - width).coerceAtLeast(0f))
        val top = (top + dy).coerceIn(0f, (imageHeight - height).coerceAtLeast(0f))
        return RectF(left, top, left + width, top + height)
    }
}
