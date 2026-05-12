package com.dwm3000.tracker.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dwm3000.tracker.vision.DetectedFace
import com.dwm3000.tracker.vision.FaceAnalysis
import com.dwm3000.tracker.vision.FaceDetectionStats
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraAnalysisController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val analysisSize: Size = DEFAULT_ANALYSIS_SIZE,
    private val onFaceResult: (Bitmap, List<DetectedFace>, FaceDetectionStats) -> Unit
) : AutoCloseable {

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraAnalysis: ImageAnalysis? = null
    private var faceAnalysis: FaceAnalysis? = null
    private var closed = false

    fun start(targetRotation: Int) {
        closed = false
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (closed) return@addListener
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    bindAnalysis(provider, targetRotation)
                } catch (e: Exception) {
                    Log.e(TAG, "Camera analysis start failed", e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun updateRotation(targetRotation: Int) {
        cameraAnalysis?.targetRotation = targetRotation
    }

    fun stop() {
        cameraAnalysis?.clearAnalyzer()
        faceAnalysis?.close()
        faceAnalysis = null

        cameraAnalysis?.let { analysis ->
            try {
                cameraProvider?.unbind(analysis)
            } catch (e: Exception) {
                Log.w(TAG, "Camera analysis unbind failed", e)
            }
        }
        cameraAnalysis = null
    }

    override fun close() {
        closed = true
        stop()
        analysisExecutor.shutdown()
    }

    private fun bindAnalysis(provider: ProcessCameraProvider, targetRotation: Int) {
        stop()

        val analyzer = FaceAnalysis(context) { bitmap, faces, stats ->
            onFaceResult(bitmap, faces, stats)
        }
        faceAnalysis = analyzer

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector())
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor, analyzer)
            }
        cameraAnalysis = analysis

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        } catch (e: Exception) {
            Log.e(TAG, "Camera analysis bind failed", e)
        }
    }

    private fun analysisResolutionSelector(): ResolutionSelector {
        return ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    analysisSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
    }

    companion object {
        private const val TAG = "CameraAnalysisController"
        private val DEFAULT_ANALYSIS_SIZE = Size(640, 480)
    }
}
