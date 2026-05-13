package com.dwm3000.tracker.tracking

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import com.dwm3000.tracker.CalibrationConfig
import com.dwm3000.tracker.vision.DetectedFace
import com.dwm3000.tracker.vision.FaceUwbAssociation
import com.dwm3000.tracker.vision.UNTRACKED_FACE_ID
import java.util.ArrayDeque
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class FaceTrackingPrediction(
    val faces: List<DetectedFace>,
    val faceSource: String,
    val predictionAgeMs: Float,
    val imuShiftX: Float,
    val imuShiftY: Float,
    val uwbShiftX: Float,
    val uwbShiftY: Float,
    val associationSummary: String = "UWB assoc: none"
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
    private var nextTrackId = 1
    private var lastBufferedUwbReceivedNs = 0L
    private var associationSummary = "UWB assoc: none"
    private val faceHistoryByTrackId = mutableMapOf<Int, ArrayDeque<FaceTrajectorySample>>()
    private val uwbHistory = ArrayDeque<UwbTrajectorySample>()

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
            if (stateImageWidth > 0 && stateImageHeight > 0 &&
                (stateImageWidth != imageWidth || stateImageHeight != imageHeight)
            ) {
                trackedFaces = trackedFaces.scaleToImage(
                    stateImageWidth,
                    stateImageHeight,
                    imageWidth,
                    imageHeight
                )
                stateImageWidth = imageWidth
                stateImageHeight = imageHeight
            }

            val detectedFaces = assignTrackIdsLocked(
                faces.sortedByDescending { it.score }.take(MAX_TRACKED_FACES)
            )
            if (detectedFaces.isEmpty()) {
                if (lastDetectionNs == 0L ||
                    frameTimestampNs - lastDetectionNs > MAX_PREDICTION_AGE_NS
                ) {
                    clearLocked()
                }
                return
            }

            trackedFaces = detectedFaces
            lastDetectionNs = frameTimestampNs
            lastStateNs = frameTimestampNs
            stateImageWidth = imageWidth
            stateImageHeight = imageHeight
            pendingFreshCnn = true
            updateUwbAnchorLocked(imageWidth, imageHeight)
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
            recordFaceTrajectoryLocked(frameTimestampNs)
            applyAssociationResultLocked(
                updateAssociationLocked(frameTimestampNs, imageWidth, imageHeight)
            )

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
                uwbShiftY = uwbShift.y,
                associationSummary = associationSummary
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
        if (trackedFaces.size != 1) return PointF()
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

    private fun updateUwbAnchorLocked(imageWidth: Int, imageHeight: Int) {
        val face = if (trackedFaces.size == 1) trackedFaces.first() else null
        if (face == null) {
            uwbFaceOffset = null
            return
        }

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

    private fun assignTrackIdsLocked(detectedFaces: List<DetectedFace>): List<DetectedFace> {
        val usedTrackIds = mutableSetOf<Int>()
        return detectedFaces.map { detection ->
            val match = trackedFaces
                .asSequence()
                .filter { it.trackId != UNTRACKED_FACE_ID }
                .filter { it.trackId !in usedTrackIds }
                .map { previous -> previous to previous.matchCost(detection) }
                .filter { (_, cost) -> cost <= MAX_TRACK_MATCH_COST }
                .minByOrNull { (_, cost) -> cost }

            val trackId = match?.first?.trackId ?: nextTrackId++
            if (match != null) usedTrackIds += trackId
            detection.copy(trackId = trackId, uwbAssociation = null)
        }
    }

    private fun recordFaceTrajectoryLocked(timestampNs: Long) {
        val activeTrackIds = trackedFaces.map { it.trackId }.toSet()
        trackedFaces.forEach { face ->
            val trackId = face.trackId
            if (trackId == UNTRACKED_FACE_ID) return@forEach
            val history = faceHistoryByTrackId.getOrPut(trackId) { ArrayDeque() }
            history.addLast(
                FaceTrajectorySample(
                    timestampNs = timestampNs,
                    centerX = face.bbox.centerX(),
                    centerY = face.bbox.centerY(),
                    height = face.bbox.height().coerceAtLeast(1f)
                )
            )
            pruneFaceHistoryLocked(history, timestampNs)
        }
        faceHistoryByTrackId.keys.retainAll(activeTrackIds)
    }

    private fun updateAssociationLocked(
        frameTimestampNs: Long,
        imageWidth: Int,
        imageHeight: Int
    ): AssociationResult {
        recordUwbTrajectoryLocked(frameTimestampNs, imageWidth, imageHeight)
        pruneUwbHistoryLocked(frameTimestampNs)

        if (trackedFaces.isEmpty()) return AssociationResult(emptyMap(), "UWB assoc: no faces")
        if (uwbHistory.isEmpty()) return AssociationResult(emptyMap(), "UWB assoc: no fresh UWB")

        val scores = trackedFaces
            .mapNotNull { face -> scoreAssociationLocked(face.trackId) }
            .sortedBy { it.cost }

        if (scores.isEmpty()) {
            return AssociationResult(emptyMap(), "UWB assoc: buffering 0/$MIN_ASSOCIATION_PAIRS")
        }

        val best = scores.first()
        val second = scores.getOrNull(1)
        val rawMargin = if (second == null) SINGLE_FACE_MARGIN else second.cost - best.cost
        val margin = if (rawMargin.isFinite()) rawMargin else 0f
        val enoughSamples = best.sampleCount >= MIN_ASSOCIATION_PAIRS
        val matched = enoughSamples &&
            best.cost <= MAX_ASSOCIATION_COST &&
            margin >= MIN_ASSOCIATION_MARGIN
        val ambiguous = enoughSamples && !matched
        val bestConfidence = confidenceFor(best.cost, margin, best.sampleCount)

        val associations = scores.associate { score ->
            val isBest = score.trackId == best.trackId
            val state = when {
                score.sampleCount < MIN_ASSOCIATION_PAIRS -> "BUFFER"
                matched && isBest -> "MATCH"
                ambiguous && isBest -> "AMBIG"
                score.cost <= best.cost + CANDIDATE_COST_BAND -> "CAND"
                else -> "ALT"
            }
            val confidence = if (isBest) bestConfidence else confidenceFor(score.cost, 0f, score.sampleCount) * 0.5f
            score.trackId to FaceUwbAssociation(
                state = state,
                confidence = confidence,
                cost = score.cost,
                margin = if (isBest) margin else 0f,
                sampleCount = score.sampleCount
            )
        }

        val summary = when {
            !enoughSamples -> "UWB assoc: buffering ${best.sampleCount}/$MIN_ASSOCIATION_PAIRS"
            matched -> "UWB -> #${best.trackId} ${(bestConfidence * 100f).toInt()}% c=${best.cost.format1()} m=${margin.format1()} n=${best.sampleCount}"
            else -> "UWB ambiguous #${best.trackId} c=${best.cost.format1()} m=${margin.format1()} n=${best.sampleCount}"
        }
        return AssociationResult(associations, summary)
    }

    private fun applyAssociationResultLocked(result: AssociationResult) {
        associationSummary = result.summary
        trackedFaces = trackedFaces.map { face ->
            face.copy(uwbAssociation = result.byTrackId[face.trackId])
        }
    }

    private fun recordUwbTrajectoryLocked(frameTimestampNs: Long, imageWidth: Int, imageHeight: Int) {
        val signals = signalStore.snapshot()
        val uwb = signals.latestUwb ?: return
        val ageNs = SystemClock.elapsedRealtimeNanos() - uwb.receivedTimeNs
        if (ageNs < 0L || ageNs > MAX_UWB_AGE_NS) return
        if (uwb.receivedTimeNs == lastBufferedUwbReceivedNs) return

        val projection = projectUwbToImage(uwb, signals.cameraRollRad, imageWidth, imageHeight)
        uwbHistory.addLast(
            UwbTrajectorySample(
                timestampNs = frameTimestampNs,
                x = projection.x,
                y = projection.y
            )
        )
        lastBufferedUwbReceivedNs = uwb.receivedTimeNs
    }

    private fun scoreAssociationLocked(trackId: Int): FaceAssociationScore? {
        if (trackId == UNTRACKED_FACE_ID) return null
        val faceHistory = faceHistoryByTrackId[trackId] ?: return null
        if (faceHistory.isEmpty()) return null

        val lateralOffsets = mutableListOf<Float>()
        val verticalOffsets = mutableListOf<Float>()
        val priorCosts = mutableListOf<Float>()
        uwbHistory.forEach { uwb ->
            val face = nearestFaceSample(faceHistory, uwb.timestampNs) ?: return@forEach
            val dx = (uwb.x - face.centerX) / face.height
            val dy = (uwb.y - face.centerY) / face.height
            lateralOffsets += dx
            verticalOffsets += dy
            priorCosts += sqrt(
                square(dx / LATERAL_SIGMA_FACE_HEIGHTS) +
                    square((dy - FRONT_POCKET_ALPHA_FACE_HEIGHTS) / VERTICAL_SIGMA_FACE_HEIGHTS)
            )
        }

        if (priorCosts.isEmpty()) {
            return FaceAssociationScore(trackId, Float.POSITIVE_INFINITY, 0)
        }

        val priorCost = priorCosts.averageFloat()
        val stabilityCost =
            variance(lateralOffsets) / square(LATERAL_STABILITY_SIGMA_FACE_HEIGHTS) +
                variance(verticalOffsets) / square(VERTICAL_STABILITY_SIGMA_FACE_HEIGHTS)
        return FaceAssociationScore(
            trackId = trackId,
            cost = priorCost + STABILITY_WEIGHT * stabilityCost,
            sampleCount = priorCosts.size
        )
    }

    private fun nearestFaceSample(
        history: ArrayDeque<FaceTrajectorySample>,
        timestampNs: Long
    ): FaceTrajectorySample? {
        var best: FaceTrajectorySample? = null
        var bestAgeNs = Long.MAX_VALUE
        history.forEach { sample ->
            val ageNs = abs(sample.timestampNs - timestampNs)
            if (ageNs < bestAgeNs) {
                bestAgeNs = ageNs
                best = sample
            }
        }
        return if (bestAgeNs <= MAX_ASSOCIATION_PAIR_AGE_NS) best else null
    }

    private fun pruneFaceHistoryLocked(history: ArrayDeque<FaceTrajectorySample>, nowNs: Long) {
        val minTimestamp = nowNs - ASSOCIATION_WINDOW_NS
        while (history.isNotEmpty() && history.first.timestampNs < minTimestamp) {
            history.removeFirst()
        }
    }

    private fun pruneUwbHistoryLocked(nowNs: Long) {
        val minTimestamp = nowNs - ASSOCIATION_WINDOW_NS
        while (uwbHistory.isNotEmpty() && uwbHistory.first.timestampNs < minTimestamp) {
            uwbHistory.removeFirst()
        }
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
        nextTrackId = 1
        lastBufferedUwbReceivedNs = 0L
        associationSummary = "UWB assoc: none"
        faceHistoryByTrackId.clear()
        uwbHistory.clear()
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

    private fun DetectedFace.matchCost(other: DetectedFace): Float {
        val centerA = bbox.center()
        val centerB = other.bbox.center()
        val distance = sqrt(square(centerA.x - centerB.x) + square(centerA.y - centerB.y))
        val scale = max(bbox.height(), other.bbox.height()).coerceAtLeast(1f)
        val iouPenalty = 1f - bbox.iou(other.bbox)
        return distance / scale + iouPenalty
    }

    private fun RectF.iou(other: RectF): Float {
        val overlapLeft = max(left, other.left)
        val overlapTop = max(top, other.top)
        val overlapRight = min(right, other.right)
        val overlapBottom = min(bottom, other.bottom)
        val overlapWidth = (overlapRight - overlapLeft).coerceAtLeast(0f)
        val overlapHeight = (overlapBottom - overlapTop).coerceAtLeast(0f)
        val intersection = overlapWidth * overlapHeight
        val union = width() * height() + other.width() * other.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun PointF.limit(maxNorm: Float) {
        val norm = kotlin.math.sqrt(x * x + y * y)
        if (norm <= maxNorm || norm == 0f) return
        val scale = maxNorm / norm
        x *= scale
        y *= scale
    }

    private fun confidenceFor(cost: Float, margin: Float, sampleCount: Int): Float {
        if (!cost.isFinite()) return 0f
        val costScore = (1f - cost / MAX_ASSOCIATION_COST).coerceIn(0f, 1f)
        val marginScore = (margin / (MIN_ASSOCIATION_MARGIN * 2f)).coerceIn(0f, 1f)
        val sampleScore = (sampleCount / TARGET_ASSOCIATION_PAIRS.toFloat()).coerceIn(0f, 1f)
        return (0.65f * costScore + 0.25f * marginScore + 0.10f * sampleScore).coerceIn(0f, 1f)
    }

    private fun variance(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.averageFloat()
        return values.fold(0f) { acc, value -> acc + square(value - mean) } / values.size
    }

    private fun List<Float>.averageFloat(): Float {
        if (isEmpty()) return 0f
        return fold(0f) { acc, value -> acc + value } / size
    }

    private fun Float.format1(): String = String.format("%.1f", this)

    private fun square(value: Float): Float = value * value

    private data class FaceTrajectorySample(
        val timestampNs: Long,
        val centerX: Float,
        val centerY: Float,
        val height: Float
    )

    private data class UwbTrajectorySample(
        val timestampNs: Long,
        val x: Float,
        val y: Float
    )

    private data class FaceAssociationScore(
        val trackId: Int,
        val cost: Float,
        val sampleCount: Int
    )

    private data class AssociationResult(
        val byTrackId: Map<Int, FaceUwbAssociation>,
        val summary: String
    )

    private companion object {
        private const val MAX_PREDICTION_AGE_NS = 1_000_000_000L
        private const val MAX_UWB_AGE_NS = 500_000_000L
        private const val ASSOCIATION_WINDOW_NS = 1_500_000_000L
        private const val MAX_ASSOCIATION_PAIR_AGE_NS = 250_000_000L
        private const val UWB_BLEND = 0.25f
        private const val MAX_UWB_SHIFT_PER_FRAME_PX = 24f
        private const val MIN_PIXEL_SHIFT = 0.1f
        private const val PARALLAX_LIMIT_M = 15f
        private const val MAX_TRACKED_FACES = 16
        private const val MAX_TRACK_MATCH_COST = 2.2f
        private const val MIN_ASSOCIATION_PAIRS = 3
        private const val TARGET_ASSOCIATION_PAIRS = 8
        private const val FRONT_POCKET_ALPHA_FACE_HEIGHTS = 4.8f
        private const val VERTICAL_SIGMA_FACE_HEIGHTS = 1.7f
        private const val LATERAL_SIGMA_FACE_HEIGHTS = 1.2f
        private const val VERTICAL_STABILITY_SIGMA_FACE_HEIGHTS = 1.0f
        private const val LATERAL_STABILITY_SIGMA_FACE_HEIGHTS = 0.9f
        private const val STABILITY_WEIGHT = 0.35f
        private const val MAX_ASSOCIATION_COST = 2.8f
        private const val MIN_ASSOCIATION_MARGIN = 0.45f
        private const val CANDIDATE_COST_BAND = 0.8f
        private const val SINGLE_FACE_MARGIN = 1.0f
    }
}
