package com.example.pixeluwb

import android.os.Build

object CalibrationConfig {
    data class DeviceCalibration(
        val uwbToCameraOffsetX: Float,
        val uwbToCameraOffsetY: Float,
        val uwbToCameraOffsetZ: Float,
        val azimuthBiasDeg: Float,
        val elevationBiasDeg: Float,
        val fallbackHFovDeg: Float,
        val fallbackVFovDeg: Float
    )

    fun getCalibration(): DeviceCalibration {
        val model = Build.MODEL.lowercase()
        return when {
            model.contains("pixel 8 pro") -> DeviceCalibration(-0.018f, 0.005f, 0f, 0f, 0f, 70f, 55f)
            model.contains("pixel 9 pro") -> DeviceCalibration(-0.016f, 0.004f, 0f, 0f, 0f, 70f, 55f)
            else -> DeviceCalibration(0f, 0f, 0f, 0f, 0f, 67f, 52f)
        }
    }
}