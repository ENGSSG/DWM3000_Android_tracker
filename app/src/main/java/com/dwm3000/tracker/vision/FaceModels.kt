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
    val frameWidth: Int,
    val frameHeight: Int,
    val conversionMs: Float,
    val inferenceMs: Float,
    val totalAnalysisMs: Float,
    val faceCount: Int,
    val faceSource: String = "CNN"
)
