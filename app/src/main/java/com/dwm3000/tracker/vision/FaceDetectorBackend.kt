package com.dwm3000.tracker.vision

import android.graphics.Bitmap

interface FaceDetectorBackend : AutoCloseable {
    val name: String

    fun detect(bitmap: Bitmap): List<DetectedFace>

    override fun close()
}
