package com.dwm3000.tracker.tracking

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import com.dwm3000.tracker.CalibrationConfig
import com.dwm3000.tracker.vision.DetectedFace
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

data class FaceTrackingPrediction(
    val faces: List<DetectedFace>,
    val faceSource: String,
    val predictionAgeMs: Float,
    val imuShiftX: Float,
    val imuShiftY: Float,
    val uwbShiftX: Float,
    val uwbShiftY: Float
)

class FacePredictionTracker(
    private val motionBuffer: CameraImuMotionBuffer,
    private val signalStore: TrackingSignalStore,
    private var horizontalFovDeg: Float = CalibrationConfig.getCalibration().fallbackHFovDeg,
    private var verticalFovDeg: Float = CalibrationConfig.getCalibration().fallbackVFovDeg
) {

    private val calibration = CalibrationConfig.getCalibration()
    private val lock = Any()

    private var trackedFaces: List<DetectedFace> = emptyList()
    private var lastDetectionNs = 0L
    private var lastStateNs = 0L
    private var stateImageWidth = 0
    private var stateImageHeight = 0
    private var uwbFaceOffset: PointF? = null
    private var pendingFreshCnn = false

    fun setCameraFov(horizontalDeg: Float, verticalDeg: Float) {
        synchronized(lock) {
            horizontalFovDeg = horizontalDeg
            verticalFovDeg = verticalDeg
        }
    }

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
            updateUwbAnchorLocked(selectedFace, imageWidth, imageHeight)
        }
    }

    fun predictForFrame(
        frameTimestampNs: Long,
        imageWidth: Int,
        imageHeight: Int
    ): FaceTrackingPrediction {
        synchronized(lock) {
            if (trackedFaces.isEmpty() || lastDetectionNs == 0L) {
                return FaceTrackingPrediction(emptyList(), "NONE", 0f, 0f, 0f, 0f, 0f)
            }

            val predictionAgeNs = frameTimestampNs - lastDetectionNs
            if (predictionAgeNs < 0L) {
                return FaceTrackingPrediction(emptyList(), "NONE", 0f, 0f, 0f, 0f, 0f)
            }
            if (predictionAgeNs > MAX_PREDICTION_AGE_NS) {
                clearLocked()
                return FaceTrackingPrediction(emptyList(), "NONE", 0f, 0f, 0f, 0f, 0f)
            }

            if (stateImageWidth != imageWidth || stateImageHeight != imageHeight) {
                trackedFaces = trackedFaces.scaleToImage(
                    stateImageWidth,
                    stateImageHeight,
                    imageWidth,
                    imageHeight
                )
                stateImageWidth = imageWidth
                stateImageHeight = imageHeight
            }

            val imuShift = motionBuffer.pixelShiftBetween(
                lastStateNs,
                frameTimestampNs,
                imageWidth,
                imageHeight
            )
            val uwbShift = computeUwbCorrectionLocked(imageWidth, imageHeight)
            val dx = imuShift.x + uwbShift.x
            val dy = imuShift.y + uwbShift.y

            if (abs(dx) >= MIN_PIXEL_SHIFT || abs(dy) >= MIN_PIXEL_SHIFT) {
                trackedFaces = trackedFaces.translateClamped(dx, dy, imageWidth, imageHeight)
            }

            lastStateNs = frameTimestampNs
            val source = if (pendingFreshCnn) "CNN" else "TRACKED"
            pendingFreshCnn = false

            return FaceTrackingPrediction(
                faces = trackedFaces,
                faceSource = source,
                predictionAgeMs = predictionAgeNs.coerceAtLeast(0L) / 1_000_000f,
                imuShiftX = imuShift.x,
                imuShiftY = imuShift.y,
                uwbShiftX = uwbShift.x,
                uwbShiftY = uwbShift.y
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

    private fun computeUwbCorrectionLocked(imageWidth: Int, imageHeight: Int): PointF {
        val offset = uwbFaceOffset ?: return PointF()
        val signals = signalStore.snapshot()
        val uwb = signals.latestUwb ?: return PointF()
        val ageNs = SystemClock.elapsedRealtimeNanos() - uwb.receivedTimeNs
        if (ageNs < 0L || ageNs > MAX_UWB_AGE_NS) return PointF()

        val projection = projectUwbToImage(uwb, signals.cameraRollRad, imageWidth, imageHeight)
        val targetX = projection.x + offset.x
        val targetY = projection.y + offset.y
        val currentFace = trackedFaces.firstOrNull() ?: return PointF()
        val currentCenter = currentFace.bbox.center()

        val correction = PointF(
            (targetX - currentCenter.x) * UWB_BLEND,
            (targetY - currentCenter.y) * UWB_BLEND
        )
        correction.limit(MAX_UWB_SHIFT_PER_FRAME_PX)
        return correction
    }

    private fun updateUwbAnchorLocked(face: DetectedFace, imageWidth: Int, imageHeight: Int) {
        val signals = signalStore.snapshot()
        val uwb = signals.latestUwb ?: run {
            uwbFaceOffset = null
            return
        }
        val ageNs = SystemClock.elapsedRealtimeNanos() - uwb.receivedTimeNs
        if (ageNs < 0L || ageNs > MAX_UWB_AGE_NS) {
            uwbFaceOffset = null
            return
        }

        val projection = projectUwbToImage(uwb, signals.cameraRollRad, imageWidth, imageHeight)
        val faceCenter = face.bbox.center()
        uwbFaceOffset = PointF(
            faceCenter.x - projection.x,
            faceCenter.y - projection.y
        )
    }

    private fun projectUwbToImage(
        uwb: TimedUwbSample,
        rollRad: Float,
        imageWidth: Int,
        imageHeight: Int
    ): PointF {
        var az = uwb.azimuthDegrees + calibration.azimuthBiasDeg
        var el = uwb.elevationDegrees + calibration.elevationBiasDeg
        if (uwb.distanceMeters < PARALLAX_LIMIT_M) {
            val adjusted = applyParallax(az, el, uwb.distanceMeters)
            az = adjusted.first
            el = adjusted.second
        }

        val tx = tan(Math.toRadians(az.toDouble())).toFloat()
        val ty = tan(Math.toRadians(el.toDouble())).toFloat()
        val cosR = cos(rollRad)
        val sinR = sin(rollRad)
        val rx = tx * cosR + ty * sinR
        val ry = -tx * sinR + ty * cosR
        val f = (max(imageWidth, imageHeight) / 2f) /
            tan(Math.toRadians((horizontalFovDeg / 2f).toDouble())).toFloat()

        return PointF(
            imageWidth / 2f + f * rx,
            imageHeight / 2f - f * ry
        )
    }

    private fun applyParallax(azimuthDeg: Float, elevationDeg: Float, distanceM: Float): Pair<Float, Float> {
        val az = Math.toRadians(azimuthDeg.toDouble())
        val el = Math.toRadians(elevationDeg.toDouble())
        val cosEl = cos(el)
        val px = distanceM * sin(az) * cosEl - calibration.uwbToCameraOffsetX
        val py = distanceM * sin(el) - calibration.uwbToCameraOffsetY
        val pz = distanceM * cos(az) * cosEl - calibration.uwbToCameraOffsetZ
        return Pair(
            Math.toDegrees(atan2(px, pz)).toFloat(),
            Math.toDegrees(atan2(py, Math.sqrt(px * px + pz * pz))).toFloat()
        )
    }

    private fun clearLocked() {
        trackedFaces = emptyList()
        lastDetectionNs = 0L
        lastStateNs = 0L
        stateImageWidth = 0
        stateImageHeight = 0
        uwbFaceOffset = null
        pendingFreshCnn = false
    }

    private fun List<DetectedFace>.translateClamped(
        dx: Float,
        dy: Float,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedFace> {
        return map { face ->
            val shiftedBox = face.bbox.translateClamped(dx, dy, imageWidth, imageHeight)
            val shiftedLandmarks = face.landmarks.map { (x, y) ->
                (x + dx).coerceIn(0f, imageWidth.toFloat()) to
                    (y + dy).coerceIn(0f, imageHeight.toFloat())
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

    private fun RectF.translateClamped(dx: Float, dy: Float, imageWidth: Int, imageHeight: Int): RectF {
        val boxWidth = width()
        val boxHeight = height()
        val left = (left + dx).coerceIn(0f, (imageWidth - boxWidth).coerceAtLeast(0f))
        val top = (top + dy).coerceIn(0f, (imageHeight - boxHeight).coerceAtLeast(0f))
        return RectF(left, top, left + boxWidth, top + boxHeight)
    }

    private fun RectF.center() = PointF(centerX(), centerY())

    private fun PointF.limit(maxNorm: Float) {
        val norm = kotlin.math.sqrt(x * x + y * y)
        if (norm <= maxNorm || norm == 0f) return
        val scale = maxNorm / norm
        x *= scale
        y *= scale
    }

    private companion object {
        private const val MAX_PREDICTION_AGE_NS = 1_000_000_000L
        private const val MAX_UWB_AGE_NS = 500_000_000L
        private const val UWB_BLEND = 0.25f
        private const val MAX_UWB_SHIFT_PER_FRAME_PX = 24f
        private const val MIN_PIXEL_SHIFT = 0.1f
        private const val PARALLAX_LIMIT_M = 15f
    }
}
