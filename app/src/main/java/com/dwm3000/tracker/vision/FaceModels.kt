package com.dwm3000.tracker.vision

import android.graphics.RectF

data class DetectedFace(
    val bbox: RectF,
    val score: Float,
    val landmarks: List<Pair<Float, Float>> = emptyList(),
    val trackId: Int = UNTRACKED_FACE_ID,
    val uwbAssociation: FaceUwbAssociation? = null
)

data class FaceUwbAssociation(
    val state: String,
    val confidence: Float,
    val cost: Float,
    val margin: Float,
    val sampleCount: Int
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
    val uwbShiftX: Float = 0f,
    val uwbShiftY: Float = 0f,
    val uwbAssociationSummary: String = "UWB assoc: none"
)

const val UNTRACKED_FACE_ID = 0
