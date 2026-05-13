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
import kotlin.math.tan

class CameraImuMotionBuffer(
    context: Context,
    private var horizontalFovDeg: Float = CalibrationConfig.getCalibration().fallbackHFovDeg,
    private var verticalFovDeg: Float = CalibrationConfig.getCalibration().fallbackVFovDeg
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val lock = Any()
    private val angularDeltas = ArrayDeque<AngularDelta>()

    private var lastTimestampNs = 0L

    fun setCameraFov(horizontalDeg: Float, verticalDeg: Float) {
        horizontalFovDeg = horizontalDeg
        verticalFovDeg = verticalDeg
    }

    fun start() {
        if (gyroSensor == null) {
            Log.w(TAG, "Gyroscope unavailable. Local IMU face prediction disabled.")
            return
        }
        lastTimestampNs = 0L
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        synchronized(lock) {
            angularDeltas.clear()
        }
        lastTimestampNs = 0L
    }

    fun pixelShiftBetween(
        fromTimestampNs: Long,
        toTimestampNs: Long,
        imageWidth: Int,
        imageHeight: Int
    ): PointF {
        if (fromTimestampNs <= 0L || toTimestampNs <= fromTimestampNs) return PointF()

        var horizontalRad = 0f
        var verticalRad = 0f
        synchronized(lock) {
            angularDeltas.forEach { delta ->
                if (delta.timestampNs > fromTimestampNs && delta.timestampNs <= toTimestampNs) {
                    horizontalRad += delta.horizontalRad
                    verticalRad += delta.verticalRad
                }
            }
        }

        if (horizontalRad == 0f && verticalRad == 0f) return PointF()

        val fx = (imageWidth / 2f) / tan(Math.toRadians((horizontalFovDeg / 2f).toDouble())).toFloat()
        val fy = (imageHeight / 2f) / tan(Math.toRadians((verticalFovDeg / 2f).toDouble())).toFloat()
        return PointF(-fx * horizontalRad, fy * verticalRad)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (lastTimestampNs == 0L) {
            lastTimestampNs = event.timestamp
            return
        }

        val dt = ((event.timestamp - lastTimestampNs) / 1_000_000_000f).coerceIn(0f, MAX_DT_SEC)
        lastTimestampNs = event.timestamp
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun pruneLocked(nowNs: Long) {
        val minTimestamp = nowNs - HISTORY_NS
        while (angularDeltas.isNotEmpty() &&
            (angularDeltas.first.timestampNs < minTimestamp || angularDeltas.size > MAX_DELTAS)
        ) {
            angularDeltas.removeFirst()
        }
    }

    private data class AngularDelta(
        val timestampNs: Long,
        val horizontalRad: Float,
        val verticalRad: Float
    )

    private companion object {
        private const val TAG = "CameraImuMotionBuffer"
        private const val MAX_DT_SEC = 0.05f
        private const val HISTORY_NS = 2_000_000_000L
        private const val MAX_DELTAS = 300
    }
}
