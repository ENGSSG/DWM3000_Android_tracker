package com.dwm3000.tracker.fusion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dwm3000.tracker.uwb.UwbRangingManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight IMU-aided predictor for visual UWB smoothing.
 *
 * UWB is still the absolute correction. Gyro predicts how a stationary peer's
 * relative vector rotates when the phone rotates; linear acceleration predicts
 * short translation between UWB samples, with strong damping so bias does not
 * run away.
 */
class UwbImuFusionManager(
    context: Context,
    private val onFusedResult: (UwbRangingManager.RangingData) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "UwbImuFusion"
        private const val SENSOR_RATE_US = 16_000
        private const val MAX_DT_SEC = 0.05f
        private const val MAX_GYRO_DT_SEC = 0.05f
        private const val MIN_DISPATCH_INTERVAL_NS = 16_000_000L
        private const val ACCEL_ALPHA = 0.18f
        private const val ACCEL_DEADBAND = 0.08f
        private const val GYRO_DELTA_DEADBAND_RAD = 0.0005f
        private const val GYRO_XY_GAIN = 0.65f
        private const val GYRO_ROLL_GAIN = 0.35f
        private const val MAX_GYRO_DELTA_RAD = 0.10f
        private const val POSITION_BLEND_XY = 0.20f
        private const val POSITION_BLEND_Z = 0.42f
        private const val VELOCITY_BLEND = 0.10f
        private const val ACCEL_BLEND = 0.015f
        private const val VELOCITY_DECAY_SEC = 0.75f
        private const val MAX_ACCEL = 4.0f
        private const val MAX_VELOCITY = 3.0f
        private const val RESIDUAL_REJECT_M = 1.25f
        private const val RESIDUAL_CAP_M = 0.45f
        private const val MAX_UWB_VELOCITY_CORRECTION = 0.65f
        private const val MAX_UWB_ACCEL_CORRECTION = 1.25f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelerationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val p = Vec3()
    private val v = Vec3()
    private val a = Vec3()
    private var initialized = false
    private var lastPredictNs = 0L
    private var lastGyroNs = 0L
    private var lastUwbNs = 0L
    private var lastDispatchNs = 0L
    private var lastPeerAddress = ""
    private var resetGeneration = 0L

    fun start() {
        lastGyroNs = 0L
        if (linearAccelerationSensor == null) {
            Log.w(TAG, "Linear acceleration sensor unavailable. UWB translation prediction disabled.")
        } else {
            sensorManager.registerListener(this, linearAccelerationSensor, SENSOR_RATE_US)
        }

        if (gyroSensor == null) {
            Log.w(TAG, "Gyroscope unavailable. UWB rotation prediction disabled.")
        } else {
            sensorManager.registerListener(this, gyroSensor, SENSOR_RATE_US)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        lastGyroNs = 0L
    }

    fun reset() {
        synchronized(lock) {
            p.clear()
            v.clear()
            a.clear()
            initialized = false
            lastPredictNs = 0L
            lastGyroNs = 0L
            lastUwbNs = 0L
            lastDispatchNs = 0L
            lastPeerAddress = ""
            resetGeneration += 1L
        }
    }

    fun processUwb(raw: UwbRangingManager.RangingData): UwbRangingManager.RangingData {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val fused = synchronized(lock) {
            val z = Vec3.fromRanging(raw.distanceMeters, raw.azimuthDegrees, raw.elevationDegrees)
            if (!initialized) {
                p.set(z)
                v.clear()
                a.clear()
                initialized = true
                lastPredictNs = nowNs
                lastUwbNs = nowNs
                lastPeerAddress = raw.peerAddress
                return@synchronized p.toRanging(lastPeerAddress)
            }

            predictTo(nowNs)
            val correctionDt = if (lastUwbNs == 0L) 0.1f else
                ((nowNs - lastUwbNs) / 1_000_000_000f).coerceIn(0.02f, 0.5f)
            val residual = z.minus(p)
            val residualNorm = residual.norm()
            if (residualNorm > RESIDUAL_REJECT_M) {
                lastUwbNs = nowNs
                lastPeerAddress = raw.peerAddress
                return@synchronized p.toRanging(lastPeerAddress)
            }

            residual.limit(RESIDUAL_CAP_M)

            p.addScaledAxes(residual, POSITION_BLEND_XY, POSITION_BLEND_XY, POSITION_BLEND_Z)

            val velocityCorrection = residual.scaled(VELOCITY_BLEND / correctionDt)
            velocityCorrection.limit(MAX_UWB_VELOCITY_CORRECTION)
            v.add(velocityCorrection)

            val accelCorrection = residual.scaled(ACCEL_BLEND / (correctionDt * correctionDt))
            accelCorrection.limit(MAX_UWB_ACCEL_CORRECTION)
            a.add(accelCorrection)
            v.limit(MAX_VELOCITY)
            a.limit(MAX_ACCEL)

            lastUwbNs = nowNs
            lastPeerAddress = raw.peerAddress
            p.toRanging(lastPeerAddress)
        }
        return fused
    }

    override fun onSensorChanged(event: SensorEvent) {
        val dispatch = synchronized(lock) {
            if (!initialized) return

            val changed = when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> handleGyroLocked(event)
                Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAccelerationLocked(event)
                else -> false
            }
            if (!changed) return

            if (event.timestamp - lastDispatchNs < MIN_DISPATCH_INTERVAL_NS) return
            lastDispatchNs = event.timestamp
            Dispatch(resetGeneration, p.toRanging(lastPeerAddress))
        }
        mainHandler.post {
            val isCurrent = synchronized(lock) {
                initialized && dispatch.generation == resetGeneration
            }
            if (isCurrent) onFusedResult(dispatch.ranging)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun predictTo(timestampNs: Long) {
        if (lastPredictNs == 0L) {
            lastPredictNs = timestampNs
            return
        }
        val dt = ((timestampNs - lastPredictNs) / 1_000_000_000f).coerceIn(0f, MAX_DT_SEC)
        if (dt <= 0f) return

        p.addScaled(v, dt)
        p.addScaled(a, 0.5f * dt * dt)
        v.addScaled(a, dt)

        val decay = exp((-dt / VELOCITY_DECAY_SEC).toDouble()).toFloat()
        v.scale(decay)
        v.limit(MAX_VELOCITY)
        lastPredictNs = timestampNs
    }

    private fun handleLinearAccelerationLocked(event: SensorEvent): Boolean {
        predictTo(event.timestamp)

        // Android sensor axes: +X right, +Y up, +Z out of screen.
        // The UWB/camera display frame uses +X right, +Y up, +Z forward
        // through the rear camera, so Android Z is inverted. For a mostly
        // stationary peer, relative target acceleration is -phone accel.
        val measured = Vec3(
            -event.values[0],
            -event.values[1],
            event.values[2]
        )
        measured.deadband(ACCEL_DEADBAND)
        measured.limit(MAX_ACCEL)
        a.scale(1f - ACCEL_ALPHA)
        a.addScaled(measured, ACCEL_ALPHA)
        return true
    }

    private fun handleGyroLocked(event: SensorEvent): Boolean {
        if (lastGyroNs == 0L) {
            lastGyroNs = event.timestamp
            return false
        }

        val dt = ((event.timestamp - lastGyroNs) / 1_000_000_000f).coerceIn(0f, MAX_GYRO_DT_SEC)
        lastGyroNs = event.timestamp
        if (dt <= 0f) return false

        predictTo(event.timestamp)

        val delta = Vec3(
            event.values[0] * dt * GYRO_XY_GAIN,
            event.values[1] * dt * GYRO_XY_GAIN,
            event.values[2] * dt * GYRO_ROLL_GAIN
        )
        val deltaNorm = delta.norm()
        if (deltaNorm < GYRO_DELTA_DEADBAND_RAD) return false
        delta.limit(MAX_GYRO_DELTA_RAD)

        p.rotateByDeviceRotation(delta.x, delta.y, delta.z)
        v.rotateByDeviceRotation(delta.x, delta.y, delta.z)
        a.rotateByDeviceRotation(delta.x, delta.y, delta.z)
        return true
    }

    private data class Dispatch(
        val generation: Long,
        val ranging: UwbRangingManager.RangingData
    )

    private data class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
        fun clear() {
            x = 0f
            y = 0f
            z = 0f
        }

        fun set(other: Vec3) {
            x = other.x
            y = other.y
            z = other.z
        }

        fun addScaled(other: Vec3, scale: Float) {
            x += other.x * scale
            y += other.y * scale
            z += other.z * scale
        }

        fun addScaledAxes(other: Vec3, scaleX: Float, scaleY: Float, scaleZ: Float) {
            x += other.x * scaleX
            y += other.y * scaleY
            z += other.z * scaleZ
        }

        fun add(other: Vec3) {
            x += other.x
            y += other.y
            z += other.z
        }

        fun scale(scale: Float) {
            x *= scale
            y *= scale
            z *= scale
        }

        fun scaled(scale: Float) = Vec3(x * scale, y * scale, z * scale)

        fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)

        fun norm(): Float = sqrt(x * x + y * y + z * z)

        fun deadband(threshold: Float) {
            if (norm() < threshold) clear()
        }

        fun limit(maxNorm: Float) {
            val n = norm()
            if (n <= maxNorm || n == 0f) return
            scale(maxNorm / n)
        }

        fun rotateByDeviceRotation(deltaX: Float, deltaY: Float, deltaZ: Float) {
            // Convert UWB/camera frame (+Z rear-camera forward) to Android
            // device frame (+Z out of screen), apply phone rotation, then
            // convert back. This direction matches UWB's observed body-frame
            // AoA behavior; gains are applied before this call to avoid leading
            // the lower-rate UWB correction.
            var deviceX = x
            var deviceY = y
            var deviceZ = -z

            val rx = deltaX
            var c = cos(rx)
            var s = sin(rx)
            var nextY = deviceY * c - deviceZ * s
            var nextZ = deviceY * s + deviceZ * c
            deviceY = nextY
            deviceZ = nextZ

            val ry = deltaY
            c = cos(ry)
            s = sin(ry)
            var nextX = deviceX * c + deviceZ * s
            nextZ = -deviceX * s + deviceZ * c
            deviceX = nextX
            deviceZ = nextZ

            val rz = deltaZ
            c = cos(rz)
            s = sin(rz)
            nextX = deviceX * c - deviceY * s
            nextY = deviceX * s + deviceY * c
            deviceX = nextX
            deviceY = nextY

            x = deviceX
            y = deviceY
            z = -deviceZ
        }

        fun toRanging(peerAddress: String): UwbRangingManager.RangingData {
            val distance = norm().coerceAtLeast(0.001f)
            val horizontal = sqrt(x * x + z * z).coerceAtLeast(0.001f)
            return UwbRangingManager.RangingData(
                distanceMeters = distance,
                azimuthDegrees = Math.toDegrees(atan2(x, z).toDouble()).toFloat(),
                elevationDegrees = Math.toDegrees(atan2(y, horizontal).toDouble()).toFloat(),
                peerAddress = peerAddress
            )
        }

        companion object {
            fun fromRanging(distance: Float, azimuthDeg: Float, elevationDeg: Float): Vec3 {
                val az = Math.toRadians(azimuthDeg.toDouble())
                val el = Math.toRadians(elevationDeg.toDouble())
                val cosEl = kotlin.math.cos(el).toFloat()
                return Vec3(
                    x = distance * kotlin.math.sin(az).toFloat() * cosEl,
                    y = distance * kotlin.math.sin(el).toFloat(),
                    z = distance * kotlin.math.cos(az).toFloat() * cosEl
                )
            }
        }
    }
}
