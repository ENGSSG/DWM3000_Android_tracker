package com.dwm3000.tracker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

class MediaPipeFaceDetector(
    context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val minDetectionConfidence: Float = 0.5f,
    private val minSuppressionThreshold: Float = 0.3f,
    enableFallback: Boolean = modelAssetPath == DEFAULT_MODEL_ASSET
) : FaceDetectorBackend {

    companion object {
        private const val TAG = "MediaPipeFaceDetector"
        const val DEFAULT_MODEL_ASSET = "models/blaze_face_full_range_sparse.tflite"
        const val SHORT_RANGE_MODEL_ASSET = "models/blaze_face_short_range.tflite"
    }

    private val primaryDetector = createDetector(context, modelAssetPath)
    private val fallbackDetector = if (enableFallback) {
        createDetector(context, SHORT_RANGE_MODEL_ASSET)
    } else {
        null
    }
    private var activeModelAssetPath = modelAssetPath

    override val name: String
        get() = modelName(activeModelAssetPath)

    override fun detect(bitmap: Bitmap): List<DetectedFace> {
        val primaryFaces = detectWith(primaryDetector, bitmap, modelAssetPath)
        if (primaryFaces.isNotEmpty() || fallbackDetector == null) {
            activeModelAssetPath = modelAssetPath
            return primaryFaces
        }

        val fallbackFaces = detectWith(fallbackDetector, bitmap, SHORT_RANGE_MODEL_ASSET)
        activeModelAssetPath = if (fallbackFaces.isNotEmpty()) SHORT_RANGE_MODEL_ASSET else modelAssetPath
        return fallbackFaces
    }

    private fun detectWith(detector: FaceDetector, bitmap: Bitmap, assetPath: String): List<DetectedFace> {
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            detector.detect(mpImage).detections().map { detection ->
                val bbox = RectF(detection.boundingBox())
                val score = detection.categories().firstOrNull()?.score() ?: 0f
                val landmarks = detection.keypoints().orElse(emptyList()).map { keypoint ->
                    keypoint.x() * bitmap.width to keypoint.y() * bitmap.height
                }
                DetectedFace(bbox, score, landmarks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "${modelName(assetPath)} detection failed", e)
            emptyList()
        }
    }

    private fun createDetector(context: Context, assetPath: String): FaceDetector {
        return FaceDetector.createFromOptions(
            context,
            FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setDelegate(Delegate.CPU)
                        .setModelAssetPath(assetPath)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(minDetectionConfidence)
                .setMinSuppressionThreshold(minSuppressionThreshold)
                .build()
        )
    }

    private fun modelName(assetPath: String): String = when (assetPath) {
        DEFAULT_MODEL_ASSET -> "BlazeFace sparse"
        SHORT_RANGE_MODEL_ASSET -> "BlazeFace short"
        else -> "BlazeFace"
    }

    override fun close() {
        primaryDetector.close()
        fallbackDetector?.close()
    }
}
