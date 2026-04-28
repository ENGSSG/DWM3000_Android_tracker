package com.dwm3000.tracker.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

/**
 * Reads the gravity sensor to compute the phone's roll angle — how much
 * it has rotated from portrait around the camera (Z) axis.
 *
 *   θ = atan2(gx, -gy)
 *
 *   portrait:            θ ≈  0°
 *   landscape top-right: θ ≈ +90°  (top of phone points right)
 *   landscape top-left:  θ ≈ -90°  (top of phone points left)
 *   upside down:         θ ≈ ±180°
 *
 * This angle is used by AoAScreenMapper to continuously rotate UWB body-frame
 * angles to screen-frame coordinates. No discrete mode switching.
 */
class GravityRollSensor(
    context: Context,
    private val onRollChanged: (rollRad: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private var smoothedRoll = 0f
    private val alpha = 0.15f

    fun start() {
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GRAVITY) return

        val gx = event.values[0]
        val gy = event.values[1]

        // When phone is nearly horizontal (camera pointing up/down),
        // gx and gy are both near zero, making θ unreliable.
        // In that case, keep the last stable angle.
        val horizontalG = Math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
        if (horizontalG < 1.5f) return  // ~8.5° from horizontal, skip update

        val roll = atan2(gx, -gy)

        // Smooth with wraparound handling at ±π
        var diff = roll - smoothedRoll
        if (diff > Math.PI) diff -= (2 * Math.PI).toFloat()
        if (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
        smoothedRoll += alpha * diff

        onRollChanged(smoothedRoll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}