package com.example.pixeluwb.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.SizeF
import kotlin.math.atan

object CameraFovHelper {
    private const val TAG = "CameraFovHelper"

    data class CameraFov(val horizontalDeg: Float, val verticalDeg: Float, val cameraId: String)

    fun getRearCameraFov(context: Context): CameraFov? = try {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val all = mutableListOf<CameraFov>()
        for (id in mgr.cameraIdList) {
            val c = mgr.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val sz: SizeF = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
            val fl = (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: continue).firstOrNull() ?: continue
            val h = Math.toDegrees(2.0 * atan((sz.width / (2.0 * fl)).toDouble())).toFloat()
            val v = Math.toDegrees(2.0 * atan((sz.height / (2.0 * fl)).toDouble())).toFloat()
            Log.d(TAG, "Camera $id: ${sz.width}x${sz.height}mm f=${fl}mm -> H=$h V=$v")
            all.add(CameraFov(h, v, id))
        }
        all.firstOrNull { it.horizontalDeg in 55f..100f }
    } catch (e: Exception) { Log.e(TAG, "Failed", e); null }
}