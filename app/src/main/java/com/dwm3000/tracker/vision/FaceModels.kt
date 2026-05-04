package com.dwm3000.tracker.vision

import android.graphics.RectF

data class DetectedFace(
    val bbox: RectF,
    val score: Float,
    val landmarks: List<Pair<Float, Float>> = emptyList()
)

data class FaceDetectionStats(
    val detectorName: String,
    val frameFps: Float,
    val inferenceMs: Float,
    val faceCount: Int,
    val detectorIntervalMs: Long,
    val imuShiftX: Float = 0f,
    val imuShiftY: Float = 0f
)
