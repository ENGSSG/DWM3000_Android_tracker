package com.dwm3000.tracker.tracking

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.dwm3000.tracker.vision.DetectedFace
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.sqrt

data class FaceRoiTrackingResult(
    val shift: PointF = PointF(),
    val pointCount: Int = 0,
    val used: Boolean = false
)

class FaceRoiTracker {

    private var previousGray: Mat? = null
    private var previousPoints: MatOfPoint2f? = null
    private var available = false

    init {
        available = try {
            OpenCVLoader.initLocal()
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV unavailable. ROI face tracking disabled.", e)
            false
        }
    }

    fun reset() {
        previousGray?.release()
        previousPoints?.release()
        previousGray = null
        previousPoints = null
    }

    fun update(
        bitmap: Bitmap,
        previousFace: DetectedFace,
        sensorPredictedFace: DetectedFace,
        imageWidth: Int,
        imageHeight: Int
    ): FaceRoiTrackingResult {
        if (!available) return FaceRoiTrackingResult()

        val currentGray = bitmap.toGrayMatOrNull() ?: return FaceRoiTrackingResult()
        val prevGray = previousGray
        val prevPoints = previousPoints

        if (prevGray == null || prevPoints == null || prevPoints.empty()) {
            replacePrevious(currentGray, seedPoints(currentGray, sensorPredictedFace.bbox, imageWidth, imageHeight))
            return FaceRoiTrackingResult()
        }

        val nextPoints = MatOfPoint2f()
        val status = MatOfByte()
        val errors = MatOfFloat()
        return try {
            Video.calcOpticalFlowPyrLK(
                prevGray,
                currentGray,
                prevPoints,
                nextPoints,
                status,
                errors,
                FLOW_WINDOW,
                FLOW_MAX_LEVEL,
                FLOW_CRITERIA,
                0,
                FLOW_MIN_EIGEN_THRESHOLD
            )

            val tracked = validTrackedPoints(
                previousPoints = prevPoints.toArray(),
                nextPoints = nextPoints.toArray(),
                status = status.toArray(),
                errors = errors.toArray(),
                searchBox = sensorPredictedFace.bbox.expand(
                    SEARCH_EXPAND_X,
                    SEARCH_EXPAND_Y,
                    imageWidth,
                    imageHeight
                )
            )

            val visualShift = tracked.medianShift().limited(MAX_ROI_SHIFT_PX)
            val sensorShift = PointF(
                sensorPredictedFace.bbox.centerX() - previousFace.bbox.centerX(),
                sensorPredictedFace.bbox.centerY() - previousFace.bbox.centerY()
            )
            val pointRatio = if (prevPoints.rows() > 0) tracked.size / prevPoints.rows().toFloat() else 0f
            val usable = tracked.size >= MIN_TRACKED_POINTS && pointRatio >= MIN_TRACKED_RATIO
            val shift = if (usable) visualShift else sensorShift
            val faceForNextFrame = previousFace.shiftedBy(shift, imageWidth, imageHeight)

            val pointsForNextFrame = if (usable && tracked.size >= MIN_RESEED_POINTS) {
                MatOfPoint2f(*tracked.map { it.next }.toTypedArray())
            } else {
                seedPoints(currentGray, faceForNextFrame.bbox, imageWidth, imageHeight)
            }
            replacePrevious(currentGray, pointsForNextFrame)

            FaceRoiTrackingResult(
                shift = shift,
                pointCount = tracked.size,
                used = usable
            )
        } catch (e: Exception) {
            Log.w(TAG, "ROI face tracking failed", e)
            replacePrevious(currentGray, seedPoints(currentGray, sensorPredictedFace.bbox, imageWidth, imageHeight))
            FaceRoiTrackingResult()
        } finally {
            nextPoints.release()
            status.release()
            errors.release()
        }
    }

    private fun validTrackedPoints(
        previousPoints: Array<Point>,
        nextPoints: Array<Point>,
        status: ByteArray,
        errors: FloatArray,
        searchBox: RectF
    ): List<TrackedPoint> {
        val count = minOf(previousPoints.size, nextPoints.size, status.size)
        return buildList {
            for (i in 0 until count) {
                if (status[i].toInt() == 0) continue
                if (i < errors.size && errors[i] > MAX_FLOW_ERROR) continue
                val next = nextPoints[i]
                if (!searchBox.contains(next.x.toFloat(), next.y.toFloat())) continue
                val previous = previousPoints[i]
                val dx = (next.x - previous.x).toFloat()
                val dy = (next.y - previous.y).toFloat()
                if (abs(dx) > MAX_POINT_SHIFT_PX || abs(dy) > MAX_POINT_SHIFT_PX) continue
                add(TrackedPoint(next = next, dx = dx, dy = dy))
            }
        }
    }

    private fun List<TrackedPoint>.medianShift(): PointF {
        if (isEmpty()) return PointF()
        val dx = map { it.dx }.sorted()[size / 2]
        val dy = map { it.dy }.sorted()[size / 2]
        return PointF(dx, dy)
    }

    private fun seedPoints(gray: Mat, bbox: RectF, imageWidth: Int, imageHeight: Int): MatOfPoint2f {
        val mask = Mat.zeros(gray.rows(), gray.cols(), CvType.CV_8UC1)
        val corners = MatOfPoint()
        return try {
            val roi = bbox.expand(SEED_EXPAND_X, SEED_EXPAND_Y, imageWidth, imageHeight)
            Imgproc.rectangle(
                mask,
                Point(roi.left.toDouble(), roi.top.toDouble()),
                Point(roi.right.toDouble(), roi.bottom.toDouble()),
                Scalar(255.0),
                -1
            )
            Imgproc.goodFeaturesToTrack(
                gray,
                corners,
                MAX_FEATURE_POINTS,
                FEATURE_QUALITY,
                FEATURE_MIN_DISTANCE,
                mask
            )
            MatOfPoint2f(*corners.toArray())
        } catch (e: Exception) {
            Log.w(TAG, "ROI feature seeding failed", e)
            MatOfPoint2f()
        } finally {
            mask.release()
            corners.release()
        }
    }

    private fun replacePrevious(gray: Mat, points: MatOfPoint2f) {
        previousGray?.release()
        previousPoints?.release()
        previousGray = gray
        previousPoints = points
    }

    private fun Bitmap.toGrayMatOrNull(): Mat? {
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(this, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            gray
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap to grayscale conversion failed", e)
            gray.release()
            null
        } finally {
            rgba.release()
        }
    }

    private fun DetectedFace.shiftedBy(dx: Float, dy: Float, imageWidth: Int, imageHeight: Int): DetectedFace {
        val shiftedBox = bbox.translateClamped(dx, dy, imageWidth, imageHeight)
        val actualDx = shiftedBox.centerX() - bbox.centerX()
        val actualDy = shiftedBox.centerY() - bbox.centerY()
        val shiftedLandmarks = landmarks.map { (x, y) ->
            (x + actualDx).coerceIn(0f, imageWidth.toFloat()) to
                (y + actualDy).coerceIn(0f, imageHeight.toFloat())
        }
        return copy(bbox = shiftedBox, landmarks = shiftedLandmarks)
    }

    private fun DetectedFace.shiftedBy(shift: PointF, imageWidth: Int, imageHeight: Int): DetectedFace {
        return shiftedBy(shift.x, shift.y, imageWidth, imageHeight)
    }

    private fun RectF.translateClamped(dx: Float, dy: Float, imageWidth: Int, imageHeight: Int): RectF {
        val boxWidth = width()
        val boxHeight = height()
        val left = (left + dx).coerceIn(0f, (imageWidth - boxWidth).coerceAtLeast(0f))
        val top = (top + dy).coerceIn(0f, (imageHeight - boxHeight).coerceAtLeast(0f))
        return RectF(left, top, left + boxWidth, top + boxHeight)
    }

    private fun RectF.expand(widthFraction: Float, heightFraction: Float, maxWidth: Int, maxHeight: Int): RectF {
        val growX = width() * widthFraction * 0.5f
        val growY = height() * heightFraction * 0.5f
        return RectF(
            (left - growX).coerceAtLeast(0f),
            (top - growY).coerceAtLeast(0f),
            (right + growX).coerceAtMost(maxWidth.toFloat()),
            (bottom + growY).coerceAtMost(maxHeight.toFloat())
        )
    }

    private fun PointF.limited(maxNorm: Float): PointF {
        val norm = sqrt(x * x + y * y)
        if (norm <= maxNorm || norm == 0f) return this
        val scale = maxNorm / norm
        return PointF(x * scale, y * scale)
    }

    private data class TrackedPoint(
        val next: Point,
        val dx: Float,
        val dy: Float
    )

    private companion object {
        private const val TAG = "FaceRoiTracker"
        private const val MAX_FEATURE_POINTS = 48
        private const val FEATURE_QUALITY = 0.01
        private const val FEATURE_MIN_DISTANCE = 6.0
        private const val MIN_TRACKED_POINTS = 6
        private const val MIN_RESEED_POINTS = 14
        private const val MIN_TRACKED_RATIO = 0.30f
        private const val MAX_FLOW_ERROR = 35f
        private const val MAX_POINT_SHIFT_PX = 70f
        private const val MAX_ROI_SHIFT_PX = 42f
        private const val SEED_EXPAND_X = 0.25f
        private const val SEED_EXPAND_Y = 0.35f
        private const val SEARCH_EXPAND_X = 1.3f
        private const val SEARCH_EXPAND_Y = 1.5f
        private val FLOW_WINDOW = Size(25.0, 25.0)
        private const val FLOW_MAX_LEVEL = 3
        private val FLOW_CRITERIA = org.opencv.core.TermCriteria(
            org.opencv.core.TermCriteria.COUNT + org.opencv.core.TermCriteria.EPS,
            20,
            0.03
        )
        private const val FLOW_MIN_EIGEN_THRESHOLD = 1e-4
    }
}
