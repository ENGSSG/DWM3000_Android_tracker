package com.dwm3000.tracker.tracking

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import com.dwm3000.tracker.CalibrationConfig
import com.dwm3000.tracker.vision.DetectedFace
import kotlin.math.abs

data class FaceTrackingPrediction(
    val faces: List<DetectedFace>,
    val faceSource: String,
    val predictionAgeMs: Float,
    val imuShiftX: Float,
    val imuShiftY: Float,
    val translationShiftX: Float,
    val translationShiftY: Float,
    val roiShiftX: Float,
    val roiShiftY: Float,
    val roiPointCount: Int,
    val roiUsed: Boolean,
    val depthMeters: Float,
    val uwbShiftX: Float,
    val uwbShiftY: Float
) {
    companion object {
        fun empty() = FaceTrackingPrediction(
            faces = emptyList(),
            faceSource = "NONE",
            predictionAgeMs = 0f,
            imuShiftX = 0f,
            imuShiftY = 0f,
            translationShiftX = 0f,
            translationShiftY = 0f,
            roiShiftX = 0f,
            roiShiftY = 0f,
            roiPointCount = 0,
            roiUsed = false,
            depthMeters = 0f,
            uwbShiftX = 0f,
            uwbShiftY = 0f
        )
    }
}

class FacePredictionTracker(
    private val motionBuffer: CameraImuMotionBuffer,
    private val signalStore: TrackingSignalStore
) {

    private val calibration = CalibrationConfig.getCalibration()
    private val roiTracker = FaceRoiTracker()
    private val lock = Any()

    private var trackedFaces: List<DetectedFace> = emptyList()
    private var lastDetectionNs = 0L
    private var lastStateNs = 0L
    private var stateImageWidth = 0
    private var stateImageHeight = 0
    private var pendingFreshCnn = false
    private var smoothedDepthMeters: Float? = null

    fun onCnnResult(
        faces: List<DetectedFace>,
        frameTimestampNs: Long,
        imageWidth: Int,
        imageHeight: Int
    ) {
        synchronized(lock) {
            val selectedFace = faces.maxByOrNull { it.score }
            if (selectedFace == null) {
                if (lastDetectionNs == 0L ||
                    frameTimestampNs - lastDetectionNs > MAX_PREDICTION_AGE_NS
                ) {
                    clearLocked()
                }
                return
            }

            trackedFaces = listOf(selectedFace)
            lastDetectionNs = frameTimestampNs
            lastStateNs = frameTimestampNs
            stateImageWidth = imageWidth
            stateImageHeight = imageHeight
            pendingFreshCnn = true
            roiTracker.reset()
        }
    }

    fun predictForFrame(
        frameTimestampNs: Long,
        bitmap: Bitmap
    ): FaceTrackingPrediction {
        synchronized(lock) {
            if (trackedFaces.isEmpty() || lastDetectionNs == 0L) {
                return FaceTrackingPrediction.empty()
            }

            val predictionAgeNs = frameTimestampNs - lastDetectionNs
            if (predictionAgeNs < 0L) {
                return FaceTrackingPrediction.empty()
            }
            if (predictionAgeNs > MAX_PREDICTION_AGE_NS) {
                clearLocked()
                return FaceTrackingPrediction.empty()
            }

            val imageWidth = bitmap.width
            val imageHeight = bitmap.height
            if (stateImageWidth != imageWidth || stateImageHeight != imageHeight) {
                trackedFaces = trackedFaces.scaleToImage(
                    stateImageWidth,
                    stateImageHeight,
                    imageWidth,
                    imageHeight
                )
                stateImageWidth = imageWidth
                stateImageHeight = imageHeight
                roiTracker.reset()
            }

            val facesBeforeMotion = trackedFaces
            val depthMeters = updateDepthEstimateLocked()
            val motion = motionBuffer.imageMotionBetween(
                lastStateNs,
                frameTimestampNs,
                imageWidth,
                imageHeight
            )
            val sensorPrediction = predictImageMotionLocked(motion, depthMeters, imageWidth, imageHeight)
            val roiResult = if (facesBeforeMotion.isNotEmpty() && sensorPrediction.faces.isNotEmpty()) {
                roiTracker.update(
                    bitmap = bitmap,
                    previousFace = facesBeforeMotion.first(),
                    sensorPredictedFace = sensorPrediction.faces.first(),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight
                )
            } else {
                FaceRoiTrackingResult()
            }

            trackedFaces = if (roiResult.used) {
                facesBeforeMotion.translateClamped(roiResult.shift.x, roiResult.shift.y, imageWidth, imageHeight)
            } else {
                sensorPrediction.faces
            }

            lastStateNs = frameTimestampNs
            val source = if (pendingFreshCnn) "CNN" else "TRACKED"
            pendingFreshCnn = false

            return FaceTrackingPrediction(
                faces = trackedFaces,
                faceSource = source,
                predictionAgeMs = predictionAgeNs.coerceAtLeast(0L) / 1_000_000f,
                imuShiftX = sensorPrediction.rotation.x,
                imuShiftY = sensorPrediction.rotation.y,
                translationShiftX = sensorPrediction.translation.x,
                translationShiftY = sensorPrediction.translation.y,
                roiShiftX = roiResult.shift.x,
                roiShiftY = roiResult.shift.y,
                roiPointCount = roiResult.pointCount,
                roiUsed = roiResult.used,
                depthMeters = depthMeters ?: 0f,
                uwbShiftX = 0f,
                uwbShiftY = 0f
            )
        }
    }

    fun hasValidStateForFrame(frameTimestampNs: Long): Boolean {
        return synchronized(lock) {
            trackedFaces.isNotEmpty() &&
                lastDetectionNs > 0L &&
                frameTimestampNs >= lastDetectionNs &&
                frameTimestampNs - lastDetectionNs <= MAX_PREDICTION_AGE_NS
        }
    }

    fun reset() {
        synchronized(lock) {
            clearLocked()
        }
    }

    private fun predictImageMotionLocked(
        motion: CameraImageMotion,
        depthMeters: Float?,
        imageWidth: Int,
        imageHeight: Int
    ): MotionPrediction {
        if (motion.isIdentity) {
            return MotionPrediction(trackedFaces, PointF(), PointF())
        }
        val firstFace = trackedFaces.firstOrNull()
            ?: return MotionPrediction(trackedFaces, PointF(), PointF())
        val beforeCenter = firstFace.bbox.center()
        val shiftedFaces = trackedFaces.projectCenters(motion, depthMeters, imageWidth, imageHeight)
        val afterCenter = shiftedFaces.firstOrNull()?.bbox?.center()
            ?: return MotionPrediction(trackedFaces, PointF(), PointF())
        val total = PointF(afterCenter.x - beforeCenter.x, afterCenter.y - beforeCenter.y)
        val faces = if (abs(total.x) >= MIN_PIXEL_SHIFT || abs(total.y) >= MIN_PIXEL_SHIFT) {
            shiftedFaces
        } else {
            trackedFaces
        }

        return MotionPrediction(
            faces = faces,
            rotation = motion.rotationShiftAt(beforeCenter.x, beforeCenter.y),
            translation = motion.translationPixelShift(depthMeters)
        )
    }

    private fun clearLocked() {
        trackedFaces = emptyList()
        lastDetectionNs = 0L
        lastStateNs = 0L
        stateImageWidth = 0
        stateImageHeight = 0
        pendingFreshCnn = false
        smoothedDepthMeters = null
        roiTracker.reset()
    }

    private fun updateDepthEstimateLocked(): Float? {
        val uwb = signalStore.snapshot().latestUwb ?: return smoothedDepthMeters
        val ageNs = SystemClock.elapsedRealtimeNanos() - uwb.receivedTimeNs
        if (ageNs < 0L || ageNs > MAX_UWB_DEPTH_AGE_NS) return smoothedDepthMeters

        val measuredDepth = (uwb.distanceMeters - calibration.uwbToCameraOffsetZ)
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(MIN_DEPTH_METERS, MAX_DEPTH_METERS)
            ?: return smoothedDepthMeters

        smoothedDepthMeters = smoothedDepthMeters?.let { previous ->
            previous * (1f - DEPTH_SMOOTHING_ALPHA) + measuredDepth * DEPTH_SMOOTHING_ALPHA
        } ?: measuredDepth
        return smoothedDepthMeters
    }

    private fun List<DetectedFace>.projectCenters(
        motion: CameraImageMotion,
        depthMeters: Float?,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedFace> {
        return map { face ->
            val center = face.bbox.center()
            val projectedCenter = motion.project(center.x, center.y, depthMeters) ?: center
            val requestedDx = projectedCenter.x - center.x
            val requestedDy = projectedCenter.y - center.y
            val shiftedBox = face.bbox.translateClamped(requestedDx, requestedDy, imageWidth, imageHeight)
            val actualDx = shiftedBox.centerX() - face.bbox.centerX()
            val actualDy = shiftedBox.centerY() - face.bbox.centerY()
            val shiftedLandmarks = face.landmarks.map { (x, y) ->
                (x + actualDx).coerceIn(0f, imageWidth.toFloat()) to
                    (y + actualDy).coerceIn(0f, imageHeight.toFloat())
            }
            face.copy(bbox = shiftedBox, landmarks = shiftedLandmarks)
        }
    }

    private fun List<DetectedFace>.scaleToImage(
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int
    ): List<DetectedFace> {
        if (oldWidth <= 0 || oldHeight <= 0) return this
        val scaleX = newWidth / oldWidth.toFloat()
        val scaleY = newHeight / oldHeight.toFloat()
        return map { face ->
            face.copy(
                bbox = RectF(
                    face.bbox.left * scaleX,
                    face.bbox.top * scaleY,
                    face.bbox.right * scaleX,
                    face.bbox.bottom * scaleY
                ),
                landmarks = face.landmarks.map { (x, y) -> x * scaleX to y * scaleY }
            )
        }
    }

    private fun List<DetectedFace>.translateClamped(
        dx: Float,
        dy: Float,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedFace> {
        return map { face ->
            val shiftedBox = face.bbox.translateClamped(dx, dy, imageWidth, imageHeight)
            val actualDx = shiftedBox.centerX() - face.bbox.centerX()
            val actualDy = shiftedBox.centerY() - face.bbox.centerY()
            val shiftedLandmarks = face.landmarks.map { (x, y) ->
                (x + actualDx).coerceIn(0f, imageWidth.toFloat()) to
                    (y + actualDy).coerceIn(0f, imageHeight.toFloat())
            }
            face.copy(bbox = shiftedBox, landmarks = shiftedLandmarks)
        }
    }

    private fun RectF.translateClamped(dx: Float, dy: Float, imageWidth: Int, imageHeight: Int): RectF {
        val boxWidth = width()
        val boxHeight = height()
        val left = (left + dx).coerceIn(0f, (imageWidth - boxWidth).coerceAtLeast(0f))
        val top = (top + dy).coerceIn(0f, (imageHeight - boxHeight).coerceAtLeast(0f))
        return RectF(left, top, left + boxWidth, top + boxHeight)
    }

    private fun RectF.center() = PointF(centerX(), centerY())

    private data class MotionPrediction(
        val faces: List<DetectedFace>,
        val rotation: PointF,
        val translation: PointF
    )

    private companion object {
        private const val MAX_PREDICTION_AGE_NS = 1_000_000_000L
        private const val MAX_UWB_DEPTH_AGE_NS = 750_000_000L
        private const val MIN_DEPTH_METERS = 0.45f
        private const val MAX_DEPTH_METERS = 8.0f
        private const val DEPTH_SMOOTHING_ALPHA = 0.25f
        private const val MIN_PIXEL_SHIFT = 0.1f
    }
}
