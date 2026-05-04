package com.dwm3000.tracker.vision

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

class GyroImageMotionSensor(
    context: Context,
    private var horizontalFovDeg: Float = CalibrationConfig.getCalibration().fallbackHFovDeg,
    private var verticalFovDeg: Float = CalibrationConfig.getCalibration().fallbackVFovDeg
) : SensorEventListener {

    companion object {
        private const val TAG = "GyroImageMotion"
        private const val MAX_DT_SEC = 0.05f
        private const val MAX_PENDING_DELTAS = 300
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val lock = Any()

    private var lastTimestampNs = 0L
    private val pendingDeltas = ArrayDeque<AngularDelta>()

    private data class AngularDelta(
        val timestampNs: Long,
        val horizontalRad: Float,
        val verticalRad: Float
    )

    fun setCameraFov(horizontalDeg: Float, verticalDeg: Float) {
        horizontalFovDeg = horizontalDeg
        verticalFovDeg = verticalDeg
    }

    fun start() {
        if (gyroSensor == null) {
            Log.w(TAG, "Gyroscope unavailable. IMU face prediction disabled.")
            return
        }
        lastTimestampNs = 0L
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        synchronized(lock) {
            pendingDeltas.clear()
        }
        lastTimestampNs = 0L
    }

    fun consumePixelShiftUntil(frameTimestampNs: Long, imageWidth: Int, imageHeight: Int): PointF {
        var hRad = 0f
        var vRad = 0f
        synchronized(lock) {
            while (pendingDeltas.isNotEmpty() && pendingDeltas.first.timestampNs <= frameTimestampNs) {
                val delta = pendingDeltas.removeFirst()
                hRad += delta.horizontalRad
                vRad += delta.verticalRad
            }
        }

        val fx = (imageWidth / 2f) / tan(Math.toRadians((horizontalFovDeg / 2f).toDouble())).toFloat()
        val fy = (imageHeight / 2f) / tan(Math.toRadians((verticalFovDeg / 2f).toDouble())).toFloat()
        return PointF(fx * hRad, fy * vRad)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (lastTimestampNs == 0L) {
            lastTimestampNs = event.timestamp
            return
        }

        val dt = ((event.timestamp - lastTimestampNs) / 1_000_000_000f).coerceIn(0f, MAX_DT_SEC)
        lastTimestampNs = event.timestamp

        val wx = event.values[0] * dt
        val wy = event.values[1] * dt

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
            pendingDeltas.addLast(AngularDelta(event.timestamp, horizontalRad, verticalRad))
            while (pendingDeltas.size > MAX_PENDING_DELTAS) {
                pendingDeltas.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
