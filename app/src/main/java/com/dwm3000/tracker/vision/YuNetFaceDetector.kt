package com.dwm3000.tracker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import java.io.File

class YuNetFaceDetector(
    context: Context,
    private val scoreThreshold: Float = 0.72f,
    private val nmsThreshold: Float = 0.3f,
    private val topK: Int = 5000
) {
    companion object {
        private const val TAG = "YuNetFaceDetector"
        private const val MODEL_ASSET = "models/face_detection_yunet_2023mar_int8.onnx"
        private const val MODEL_FILE = "face_detection_yunet_2023mar_int8.onnx"
    }

    private val modelPath = copyModelToFiles(context)
    private var detector: FaceDetectorYN? = null
    private var inputWidth = 0
    private var inputHeight = 0

    val name = "YuNet"

    init {
        check(OpenCVLoader.initLocal() || OpenCVLoader.initDebug()) {
            "OpenCV native library failed to initialize"
        }
    }

    fun detect(bitmap: Bitmap): List<DetectedFace> {
        ensureDetector(bitmap.width, bitmap.height)

        val rgba = Mat()
        val bgr = Mat()
        val faces = Mat()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            detector?.detect(bgr, faces)
            parseFaces(faces)
        } catch (e: Exception) {
            Log.e(TAG, "YuNet detection failed", e)
            emptyList()
        } finally {
            rgba.release()
            bgr.release()
            faces.release()
        }
    }

    private fun ensureDetector(width: Int, height: Int) {
        if (detector == null) {
            detector = FaceDetectorYN.create(
                modelPath,
                "",
                Size(width.toDouble(), height.toDouble()),
                scoreThreshold,
                nmsThreshold,
                topK,
                Dnn.DNN_BACKEND_OPENCV,
                Dnn.DNN_TARGET_CPU
            )
            inputWidth = width
            inputHeight = height
            return
        }

        if (width != inputWidth || height != inputHeight) {
            detector?.setInputSize(Size(width.toDouble(), height.toDouble()))
            inputWidth = width
            inputHeight = height
        }
    }

    private fun parseFaces(faces: Mat): List<DetectedFace> {
        if (faces.empty() || faces.cols() < 15) return emptyList()

        val out = ArrayList<DetectedFace>(faces.rows())
        for (row in 0 until faces.rows()) {
            val x = faces.get(row, 0)[0].toFloat()
            val y = faces.get(row, 1)[0].toFloat()
            val w = faces.get(row, 2)[0].toFloat()
            val h = faces.get(row, 3)[0].toFloat()
            val score = faces.get(row, 14)[0].toFloat()
            val landmarks = (0 until 5).map { i ->
                faces.get(row, 4 + i * 2)[0].toFloat() to faces.get(row, 5 + i * 2)[0].toFloat()
            }
            out += DetectedFace(RectF(x, y, x + w, y + h), score, landmarks)
        }
        return out
    }

    private fun copyModelToFiles(context: Context): String {
        val outFile = File(context.filesDir, MODEL_FILE)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath

        context.assets.open(MODEL_ASSET).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile.absolutePath
    }
}
