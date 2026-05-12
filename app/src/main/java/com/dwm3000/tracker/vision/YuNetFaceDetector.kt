package com.dwm3000.tracker.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN

class YuNetFaceDetector(context: Context) : FaceDetectorBackend {

    override val name: String = "OpenCV YuNet"

    private val detector: FaceDetectorYN

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV initialization failed" }
        val model = AssetFiles.copyToCache(context.applicationContext, MODEL_ASSET)
        detector = FaceDetectorYN.create(
            model.absolutePath,
            "",
            Size(320.0, 320.0),
            SCORE_THRESHOLD,
            NMS_THRESHOLD,
            TOP_K
        )
    }

    override fun detect(bitmap: Bitmap): List<DetectedFace> {
        val image = Mat()
        val faces = Mat()
        return try {
            Utils.bitmapToMat(bitmap, image)
            Imgproc.cvtColor(image, image, Imgproc.COLOR_RGBA2BGR)

            val width = image.cols()
            val height = image.rows()
            detector.setInputSize(Size(width.toDouble(), height.toDouble()))
            detector.detect(image, faces)

            buildList {
                for (row in 0 until faces.rows()) {
                    if (faces.cols() < MIN_FACE_COLUMNS) continue

                    val x = faces.valueAt(row, 0).toFloat()
                    val y = faces.valueAt(row, 1).toFloat()
                    val boxWidth = faces.valueAt(row, 2).toFloat()
                    val boxHeight = faces.valueAt(row, 3).toFloat()
                    val score = faces.valueAt(row, faces.cols() - 1).toFloat()
                    if (score < SCORE_THRESHOLD) continue

                    val bbox = RectF(
                        x.coerceIn(0f, width.toFloat()),
                        y.coerceIn(0f, height.toFloat()),
                        (x + boxWidth).coerceIn(0f, width.toFloat()),
                        (y + boxHeight).coerceIn(0f, height.toFloat())
                    )
                    if (bbox.width() <= 1f || bbox.height() <= 1f) continue

                    add(
                        DetectedFace(
                            bbox = bbox,
                            score = score
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "YuNet detection failed", e)
            emptyList()
        } finally {
            image.release()
            faces.release()
        }
    }

    override fun close() {
        // OpenCV Java bindings do not expose a close hook for FaceDetectorYN.
    }

    private fun Mat.valueAt(row: Int, col: Int): Double {
        val value = get(row, col)
        return if (value == null || value.isEmpty()) 0.0 else value[0]
    }

    companion object {
        private const val TAG = "YuNetFaceDetector"
        private const val MODEL_ASSET = "models/face_detection_yunet_2023mar.onnx"
        private const val SCORE_THRESHOLD = 0.5f
        private const val NMS_THRESHOLD = 0.3f
        private const val TOP_K = 5000
        private const val MIN_FACE_COLUMNS = 5
    }
}
