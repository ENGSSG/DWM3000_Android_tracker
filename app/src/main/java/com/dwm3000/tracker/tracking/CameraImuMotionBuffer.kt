package com.dwm3000.tracker.tracking

import android.content.Context
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.dwm3000.tracker.CalibrationConfig
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class CameraImageMotion internal constructor(
    val horizontalRad: Float,
    val verticalRad: Float,
    private val rightMeters: Float,
    private val downMeters: Float,
    private val fx: Float,
    private val fy: Float,
    private val cx: Float,
    private val cy: Float
) {
    val isIdentity: Boolean
        get() = horizontalRad == 0f && verticalRad == 0f && rightMeters == 0f && downMeters == 0f

    fun project(x: Float, y: Float, depthMeters: Float?): PointF? {
        val rotated = projectRotation(x, y) ?: return null
        val translation = translationPixelShift(depthMeters)
        return PointF(rotated.x + translation.x, rotated.y + translation.y)
    }

    fun rotationShiftAt(x: Float, y: Float): PointF {
        val rotated = projectRotation(x, y) ?: return PointF()
        return PointF(rotated.x - x, rotated.y - y)
    }

    fun translationPixelShift(depthMeters: Float?): PointF {
        if (depthMeters == null || depthMeters <= 0f || (rightMeters == 0f && downMeters == 0f)) {
            return PointF()
        }
        return PointF(
            -fx * rightMeters / depthMeters,
            -fy * downMeters / depthMeters
        ).limited(MAX_TRANSLATION_SHIFT_PX)
    }

    private fun projectRotation(x: Float, y: Float): PointF? {
        if (horizontalRad == 0f && verticalRad == 0f) return PointF(x, y)

        val rayX = (x - cx) / fx
        val rayY = (y - cy) / fy
        val rayZ = 1f

        // Convert the integrated camera motion into a view-rotation homography.
        // The signs preserve the previous observed direction while using the
        // point's camera ray instead of one constant pixel shift for the frame.
        val yawRad = -horizontalRad
        val pitchRad = -verticalRad
        val cosYaw = cos(yawRad)
        val sinYaw = sin(yawRad)
        val cosPitch = cos(pitchRad)
        val sinPitch = sin(pitchRad)

        val yawX = rayX * cosYaw + rayZ * sinYaw
        val yawZ = -rayX * sinYaw + rayZ * cosYaw
        val pitchY = rayY * cosPitch - yawZ * sinPitch
        val pitchZ = rayY * sinPitch + yawZ * cosPitch
        if (pitchZ <= MIN_PROJECTED_Z) return null

        return PointF(
            cx + fx * (yawX / pitchZ),
            cy + fy * (pitchY / pitchZ)
        )
    }

    internal companion object {
        val IDENTITY = CameraImageMotion(0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f)
        private const val MIN_PROJECTED_Z = 0.05f
        private const val MAX_TRANSLATION_SHIFT_PX = 18f
    }
}

class CameraImuMotionBuffer(
    context: Context,
    private var horizontalFovDeg: Float = CalibrationConfig.getCalibration().fallbackHFovDeg,
    private var verticalFovDeg: Float = CalibrationConfig.getCalibration().fallbackVFovDeg
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccelerationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val lock = Any()
    private val angularDeltas = ArrayDeque<AngularDelta>()
    private val translationDeltas = ArrayDeque<TranslationDelta>()

    private var lastGyroTimestampNs = 0L
    private var lastLinearTimestampNs = 0L
    private var velocityRightMps = 0f
    private var velocityDownMps = 0f

    fun setCameraFov(horizontalDeg: Float, verticalDeg: Float) {
        horizontalFovDeg = horizontalDeg
        verticalFovDeg = verticalDeg
    }

    fun start() {
        lastGyroTimestampNs = 0L
        lastLinearTimestampNs = 0L
        velocityRightMps = 0f
        velocityDownMps = 0f

        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Log.w(TAG, "Gyroscope unavailable. Local IMU face prediction disabled.")
        }

        if (linearAccelerationSensor != null) {
            sensorManager.registerListener(this, linearAccelerationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Log.w(TAG, "Linear acceleration unavailable. Depth-scaled translation prediction disabled.")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        synchronized(lock) {
            angularDeltas.clear()
            translationDeltas.clear()
        }
        lastGyroTimestampNs = 0L
        lastLinearTimestampNs = 0L
        velocityRightMps = 0f
        velocityDownMps = 0f
    }

    fun imageMotionBetween(
        fromTimestampNs: Long,
        toTimestampNs: Long,
        imageWidth: Int,
        imageHeight: Int
    ): CameraImageMotion {
        if (fromTimestampNs <= 0L || toTimestampNs <= fromTimestampNs) {
            return CameraImageMotion.IDENTITY
        }

        var horizontalRad = 0f
        var verticalRad = 0f
        var rightMeters = 0f
        var downMeters = 0f
        synchronized(lock) {
            angularDeltas.forEach { delta ->
                if (delta.timestampNs > fromTimestampNs && delta.timestampNs <= toTimestampNs) {
                    horizontalRad += delta.horizontalRad
                    verticalRad += delta.verticalRad
                }
            }
            translationDeltas.forEach { delta ->
                if (delta.timestampNs > fromTimestampNs && delta.timestampNs <= toTimestampNs) {
                    rightMeters += delta.rightMeters
                    downMeters += delta.downMeters
                }
            }
        }

        if (horizontalRad == 0f && verticalRad == 0f && rightMeters == 0f && downMeters == 0f) {
            return CameraImageMotion.IDENTITY
        }

        val fx = (imageWidth / 2f) / tan(Math.toRadians((horizontalFovDeg / 2f).toDouble())).toFloat()
        val fy = (imageHeight / 2f) / tan(Math.toRadians((verticalFovDeg / 2f).toDouble())).toFloat()
        return CameraImageMotion(
            horizontalRad = horizontalRad,
            verticalRad = verticalRad,
            rightMeters = rightMeters,
            downMeters = downMeters,
            fx = fx,
            fy = fy,
            cx = imageWidth / 2f,
            cy = imageHeight / 2f
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAcceleration(event)
        }
    }

    private fun handleGyroscope(event: SensorEvent) {
        if (lastGyroTimestampNs == 0L) {
            lastGyroTimestampNs = event.timestamp
            return
        }

        val dt = ((event.timestamp - lastGyroTimestampNs) / 1_000_000_000f).coerceIn(0f, MAX_DT_SEC)
        lastGyroTimestampNs = event.timestamp
        if (dt <= 0f) return

        val wx = event.values[0] * dt
        val wy = event.values[1] * dt

        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val horizontalRad: Float
        val verticalRad: Float
        when (rotation) {
            Surface.ROTATION_90 -> {
                horizontalRad = wx
                verticalRad = wy
            }
            Surface.ROTATION_180 -> {
                horizontalRad = wy
                verticalRad = -wx
            }
            Surface.ROTATION_270 -> {
                horizontalRad = -wx
                verticalRad = -wy
            }
            else -> {
                horizontalRad = -wy
                verticalRad = wx
            }
        }

        synchronized(lock) {
            angularDeltas.addLast(AngularDelta(event.timestamp, horizontalRad, verticalRad))
            pruneLocked(event.timestamp)
        }
    }

    private fun handleLinearAcceleration(event: SensorEvent) {
        if (lastLinearTimestampNs == 0L) {
            lastLinearTimestampNs = event.timestamp
            return
        }

        val dt = ((event.timestamp - lastLinearTimestampNs) / 1_000_000_000f).coerceIn(0f, MAX_DT_SEC)
        lastLinearTimestampNs = event.timestamp
        if (dt <= 0f) return

        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val (rightAccel, downAccel) = mapAccelerationToImageAxes(
            sanitizeAcceleration(event.values[0]),
            sanitizeAcceleration(event.values[1]),
            rotation
        )

        val rightDelta = velocityRightMps * dt + 0.5f * rightAccel * dt * dt
        val downDelta = velocityDownMps * dt + 0.5f * downAccel * dt * dt

        velocityRightMps = (velocityRightMps + rightAccel * dt)
            .coerceIn(-MAX_VELOCITY_MPS, MAX_VELOCITY_MPS)
        velocityDownMps = (velocityDownMps + downAccel * dt)
            .coerceIn(-MAX_VELOCITY_MPS, MAX_VELOCITY_MPS)

        val damping = exp((-dt / VELOCITY_DECAY_SEC).toDouble()).toFloat()
        velocityRightMps *= damping
        velocityDownMps *= damping

        val limitedDelta = PointF(rightDelta, downDelta).limited(MAX_TRANSLATION_DELTA_M)
        if (limitedDelta.x == 0f && limitedDelta.y == 0f) return

        synchronized(lock) {
            translationDeltas.addLast(
                TranslationDelta(
                    timestampNs = event.timestamp,
                    rightMeters = limitedDelta.x,
                    downMeters = limitedDelta.y
                )
            )
            pruneLocked(event.timestamp)
        }
    }

    private fun mapAccelerationToImageAxes(
        deviceX: Float,
        deviceY: Float,
        rotation: Int
    ): Pair<Float, Float> {
        return when (rotation) {
            Surface.ROTATION_90 -> deviceY to deviceX
            Surface.ROTATION_180 -> -deviceX to deviceY
            Surface.ROTATION_270 -> -deviceY to -deviceX
            else -> deviceX to -deviceY
        }
    }

    private fun sanitizeAcceleration(value: Float): Float {
        if (abs(value) < ACCEL_DEADBAND_MPS2) return 0f
        return value.coerceIn(-MAX_ACCEL_MPS2, MAX_ACCEL_MPS2)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun pruneLocked(nowNs: Long) {
        val minTimestamp = nowNs - HISTORY_NS
        while (angularDeltas.isNotEmpty() &&
            (angularDeltas.first.timestampNs < minTimestamp || angularDeltas.size > MAX_DELTAS)
        ) {
            angularDeltas.removeFirst()
        }
        while (translationDeltas.isNotEmpty() &&
            (translationDeltas.first.timestampNs < minTimestamp || translationDeltas.size > MAX_DELTAS)
        ) {
            translationDeltas.removeFirst()
        }
    }

    private data class AngularDelta(
        val timestampNs: Long,
        val horizontalRad: Float,
        val verticalRad: Float
    )

    private data class TranslationDelta(
        val timestampNs: Long,
        val rightMeters: Float,
        val downMeters: Float
    )

    private companion object {
        private const val TAG = "CameraImuMotionBuffer"
        private const val MAX_DT_SEC = 0.05f
        private const val HISTORY_NS = 2_000_000_000L
        private const val MAX_DELTAS = 300
        private const val ACCEL_DEADBAND_MPS2 = 0.12f
        private const val MAX_ACCEL_MPS2 = 3.0f
        private const val MAX_VELOCITY_MPS = 0.6f
        private const val VELOCITY_DECAY_SEC = 0.18f
        private const val MAX_TRANSLATION_DELTA_M = 0.02f
    }
}

private fun PointF.limited(maxNorm: Float): PointF {
    val norm = sqrt(x * x + y * y)
    if (norm <= maxNorm || norm == 0f) return this
    val scale = maxNorm / norm
    return PointF(x * scale, y * scale)
}
