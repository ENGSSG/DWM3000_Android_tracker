package com.example.pixeluwb.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager

/**
 * Tracks the device's orientation (pitch and roll) using the rotation vector
 * sensor, which fuses accelerometer, gyroscope, and magnetometer data.
 *
 * This is needed so the AR overlay can compensate for the phone not being
 * held perfectly upright. When the user tilts the phone forward (pitch),
 * the UWB elevation angle relative to the camera view changes.
 *
 * Usage:
 *   val orientationManager = SensorOrientationManager(context) { pitch, roll ->
 *       mapper.updateDeviceOrientation(pitch, roll)
 *   }
 *   orientationManager.start()
 *   // ...
 *   orientationManager.stop()
 */
class SensorOrientationManager(
    private val context: Context,
    private val onOrientationChanged: (pitchDeg: Float, rollDeg: Float) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "SensorOrientation"
        private const val SENSOR_RATE_US = 33_000  // ~30 Hz
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val remappedMatrix = FloatArray(9)

    // Smoothing with exponential moving average
    private var smoothedPitch = 0f
    private var smoothedRoll = 0f
    private val smoothingAlpha = 0.15f  // Lower = smoother but laggier

    fun start() {
        if (rotationSensor == null) {
            Log.w(TAG, "Rotation vector sensor not available. Tilt compensation disabled.")
            return
        }
        sensorManager.registerListener(this, rotationSensor, SENSOR_RATE_US)
        Log.d(TAG, "Orientation tracking started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Orientation tracking stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        // Get rotation matrix from rotation vector
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // Remap coordinate system for the current display rotation.
        // In portrait mode (Surface.ROTATION_0):
        //   Device X = rightward, Y = upward, Z = out of screen.
        // The default remapping (AXIS_X, AXIS_Y) is correct for portrait.
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation

        when (rotation) {
            Surface.ROTATION_0 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Y,
                    remappedMatrix
                )
            }
            Surface.ROTATION_90 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    remappedMatrix
                )
            }
            Surface.ROTATION_270 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X,
                    remappedMatrix
                )
            }
            else -> {
                System.arraycopy(rotationMatrix, 0, remappedMatrix, 0, 9)
            }
        }

        SensorManager.getOrientation(remappedMatrix, orientationAngles)

        // orientationAngles[0] = azimuth (yaw) — not needed for tilt correction
        // orientationAngles[1] = pitch (tilt forward/backward)
        //   0 = phone vertical (upright), -π/2 = phone flat face-up
        // orientationAngles[2] = roll (tilt left/right)
        //   0 = no side tilt

        val rawPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        val rawRoll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

        // When the phone is held in "AR mode" (roughly vertical, pointing at something),
        // pitch is close to 0°. When tilted back (user looking down at phone), pitch
        // goes negative toward -90°.
        //
        // For AR overlay correction, we want:
        //   pitchDeg = 0  when phone is perfectly vertical
        //   pitchDeg > 0  when top of phone tilts away from user
        //   pitchDeg < 0  when top of phone tilts toward user
        //
        // The SensorManager convention: pitch = 0 at vertical, negative when tilting
        // screen toward the sky. So we negate it for our convention.
        val pitchDeg = -rawPitch
        val rollDeg = rawRoll

        // Apply exponential smoothing to reduce jitter
        smoothedPitch = smoothedPitch + smoothingAlpha * (pitchDeg - smoothedPitch)
        smoothedRoll = smoothedRoll + smoothingAlpha * (rollDeg - smoothedRoll)

        onOrientationChanged(smoothedPitch, smoothedRoll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}