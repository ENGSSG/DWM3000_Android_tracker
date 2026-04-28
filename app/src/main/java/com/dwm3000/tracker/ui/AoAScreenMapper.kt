package com.dwm3000.tracker.ui

import android.graphics.PointF
import android.util.Log
import com.dwm3000.tracker.CalibrationConfig
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Maps UWB AoA (azimuth, elevation) → screen pixel coordinates.
 *
 * ━━━ COORDINATE SYSTEMS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * UWB reports angles in the device BODY frame (fixed to the phone hardware):
 *   +azimuth  → body RIGHT (toward body +X in portrait; confirmed on Pixel 8/9 Pro)
 *   +elevation → body UP (toward body +Y in portrait)
 *
 * The camera preview (CameraX + PreviewView) is always displayed so that
 * "physical up" = "screen up". This means the preview IMAGE rotates with
 * the phone, but the UWB ANGLES do not — they stay in the body frame.
 *
 * ━━━ CONTINUOUS ROTATION ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * We use the gravity sensor to get the phone's actual roll angle θ:
 *   θ = atan2(gx, -gy)
 *   portrait: θ ≈ 0°, landscape (top-right): θ ≈ +90°, landscape (top-left): θ ≈ -90°
 *
 * Then project and apply inverse rotation R(-θ) to match CameraX's rotated preview:
 *   tx = tan(Az), ty = tan(El)
 *   screen_x = f·( tx·cos(θ) + ty·sin(θ))
 *   screen_y = f·(-tx·sin(θ) + ty·cos(θ))
 *
 * This is smooth and continuous — no discrete mode switching, no jumps.
 *
 * ━━━ FOCAL LENGTH ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *   f = max(screenW, screenH) / 2 / tan(sensorHFov / 2)
 */
class AoAScreenMapper {

    companion object { private const val TAG = "AoAScreenMapper" }

    private val cal = CalibrationConfig.getCalibration()
    private var sensorHFov = cal.fallbackHFovDeg

    /** Current phone roll angle in radians, updated by gravity sensor. */
    var rollRad: Float = 0f

    fun setCameraFov(hDeg: Float, vDeg: Float) {
        sensorHFov = hDeg
        Log.d(TAG, "FoV set: H=$hDeg V=$vDeg")
    }

    fun mapToScreen(
        azDeg: Float, elDeg: Float, distM: Float,
        screenW: Int, screenH: Int
    ): PointF {
        // 1) Bias
        var az = azDeg + cal.azimuthBiasDeg
        var el = elDeg + cal.elevationBiasDeg

        // 2) Parallax
        if (distM < 15f) {
            val r = parallax(az, el, distM)
            az = r.first; el = r.second
        }

        // 3) Rotate body-frame camera image to screen-frame.
        //
        // CameraX rotates the preview image by the phone's roll angle θ so that
        // "physical up" always appears as "screen up". We apply the same rotation
        // to the UWB projection coordinates.
        //
        // UWB sign convention (confirmed empirically on Pixel 8/9 Pro):
        //   +Az = RIGHT in body frame,  +El = UP in body frame
        //
        // θ = atan2(gx, -gy) from gravity sensor (see GravityRollSensor).
        //
        // Pinhole projection in body frame: tx = tan(Az), ty = tan(El)
        // Inverse-rotated to screen frame (R(-θ)):
        //   rx =  tx·cos(θ) + ty·sin(θ)
        //   ry = -tx·sin(θ) + ty·cos(θ)
        //
        val tx = tan(Math.toRadians(az.toDouble())).toFloat()
        val ty = tan(Math.toRadians(el.toDouble())).toFloat()
        val cosR = cos(rollRad)
        val sinR = sin(rollRad)
        val rx =  tx * cosR + ty * sinR
        val ry = -tx * sinR + ty * cosR

        // 4) Focal length
        val f = (max(screenW, screenH) / 2f) / tan(Math.toRadians((sensorHFov / 2f).toDouble())).toFloat()

        // 5) Project to screen pixels
        val x = screenW / 2f + f * rx
        val y = screenH / 2f - f * ry

        return PointF(x, y)
    }

    fun isInFov(azDeg: Float, elDeg: Float): Boolean {
        val limit = sensorHFov / 2f
        return kotlin.math.abs(azDeg) <= limit && kotlin.math.abs(elDeg) <= limit
    }

    private fun parallax(azDeg: Float, elDeg: Float, distM: Float): Pair<Float, Float> {
        val a = Math.toRadians(azDeg.toDouble()); val e = Math.toRadians(elDeg.toDouble())
        val ce = cos(e)
        val px = distM * sin(a) * ce - cal.uwbToCameraOffsetX
        val py = distM * sin(e) - cal.uwbToCameraOffsetY
        val pz = distM * cos(a) * ce - cal.uwbToCameraOffsetZ
        return Pair(
            Math.toDegrees(atan2(px, pz)).toFloat(),
            Math.toDegrees(atan2(py, Math.sqrt(px * px + pz * pz))).toFloat()
        )
    }
}