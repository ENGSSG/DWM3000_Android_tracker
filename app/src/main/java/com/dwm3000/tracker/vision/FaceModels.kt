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
    val faceSource: String = "CNN",
    val detectorIntervalMs: Long = 0L,
    val predictionAgeMs: Float = 0f,
    val imuShiftX: Float = 0f,
    val imuShiftY: Float = 0f,
    val translationShiftX: Float = 0f,
    val translationShiftY: Float = 0f,
    val roiShiftX: Float = 0f,
    val roiShiftY: Float = 0f,
    val roiPointCount: Int = 0,
    val roiUsed: Boolean = false,
    val depthMeters: Float = 0f,
    val uwbShiftX: Float = 0f,
    val uwbShiftY: Float = 0f
)
