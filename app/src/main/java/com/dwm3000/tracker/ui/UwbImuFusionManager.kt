package com.dwm3000.tracker.ui

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
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Lightweight IMU-aided predictor for visual UWB smoothing.
 *
 * UWB is still the absolute correction. The phone linear-acceleration sensor
 * only predicts short motion between UWB samples, with strong damping so bias
 * does not run away.
 */
class UwbImuFusionManager(
    context: Context,
    private val onFusedResult: (UwbRangingManager.RangingData) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "UwbImuFusion"
        private const val SENSOR_RATE_US = 16_000
        private const val MAX_DT_SEC = 0.05f
        private const val MIN_DISPATCH_INTERVAL_NS = 16_000_000L
        private const val ACCEL_ALPHA = 0.18f
        private const val ACCEL_DEADBAND = 0.08f
        private const val POSITION_BLEND = 0.42f
        private const val VELOCITY_BLEND = 0.10f
        private const val ACCEL_BLEND = 0.015f
        private const val VELOCITY_DECAY_SEC = 0.75f
        private const val MAX_ACCEL = 4.0f
        private const val MAX_VELOCITY = 3.0f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelerationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val p = Vec3()
    private val v = Vec3()
    private val a = Vec3()
    private var initialized = false
    private var lastPredictNs = 0L
    private var lastUwbNs = 0L
    private var lastDispatchNs = 0L
    private var lastPeerAddress = ""
    private var resetGeneration = 0L

    fun start() {
        if (linearAccelerationSensor == null) {
            Log.w(TAG, "Linear acceleration sensor unavailable. UWB smoothing uses UWB only.")
            return
        }
        sensorManager.registerListener(this, linearAccelerationSensor, SENSOR_RATE_US)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun reset() {
        synchronized(lock) {
            p.clear()
            v.clear()
            a.clear()
            initialized = false
            lastPredictNs = 0L
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

            p.addScaled(residual, POSITION_BLEND)
            v.addScaled(residual, VELOCITY_BLEND / correctionDt)
            a.addScaled(residual, ACCEL_BLEND / (correctionDt * correctionDt))
            v.limit(MAX_VELOCITY)
            a.limit(MAX_ACCEL)

            lastUwbNs = nowNs
            lastPeerAddress = raw.peerAddress
            p.toRanging(lastPeerAddress)
        }
        return fused
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
        val generation: Long
        val fused = synchronized(lock) {
            if (!initialized) return

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

            if (event.timestamp - lastDispatchNs < MIN_DISPATCH_INTERVAL_NS) return
            lastDispatchNs = event.timestamp
            generation = resetGeneration
            p.toRanging(lastPeerAddress)
        }
        mainHandler.post {
            val isCurrent = synchronized(lock) { initialized && generation == resetGeneration }
            if (isCurrent) onFusedResult(fused)
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

        fun scale(scale: Float) {
            x *= scale
            y *= scale
            z *= scale
        }

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
