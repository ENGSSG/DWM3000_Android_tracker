package com.dwm3000.tracker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.dwm3000.tracker.camera.CameraAnalysisController
import com.dwm3000.tracker.databinding.ActivityMainBinding
import com.dwm3000.tracker.ranging.RangingSessionCoordinator
import com.dwm3000.tracker.uwb.UwbRangingManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // Stage 2: privacy-processed camera image + AR reticle + radar enabled.
        private const val STAGE_2 = true
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraAnalysisController
    private lateinit var rangingCoordinator: RangingSessionCoordinator
    private var gravitySensor: GravityRollSensor? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.UWB_RANGING, Manifest.permission.CAMERA
    )

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.all { it.value }) { updateStatus("Ready. Select a role."); if (STAGE_2) startCamera() }
        else { updateStatus("Missing permissions."); Toast.makeText(this, "All permissions required", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()
        binding.hudTop.post { updateFaceStatsPosition() }
        cameraController = CameraAnalysisController(this, this) { bitmap, faces, stats ->
            binding.faceBlurOverlay.post {
                binding.faceBlurOverlay.updateDetections(bitmap, faces, stats)
            }
        }
        rangingCoordinator = RangingSessionCoordinator(
            context = this,
            scope = lifecycleScope,
            onStatusChanged = ::updateStatus,
            onRangingStarted = { showTelemetry(true) },
            onRangingUpdate = ::renderTelemetry,
            onStartFailed = { setButtonsEnabled(true) },
            onPeerLost = { clearPeerTelemetry() }
        )

        if (STAGE_2) {
            CameraFovHelper.getRearCameraFov(this)?.let {
                Log.d(TAG, "Camera FoV: H=${it.horizontalDeg} V=${it.verticalDeg}")
                binding.arOverlay.mapper.setCameraFov(it.horizontalDeg, it.verticalDeg)
            }
            gravitySensor = GravityRollSensor(this) { rollRad ->
                binding.arOverlay.mapper.rollRad = rollRad
            }
        }

        binding.btnController.setOnClickListener { startAsController() }
        binding.btnControlee.setOnClickListener { startAsControlee() }
        binding.btnStop.setOnClickListener { stopEverything() }

        if (hasAllPermissions()) { updateStatus("Ready. Select a role."); if (STAGE_2) startCamera() }
        else permLauncher.launch(requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray())
    }

    override fun onResume() { super.onResume(); gravitySensor?.start(); rangingCoordinator.startSensors() }
    override fun onPause() { super.onPause(); gravitySensor?.stop(); rangingCoordinator.stopSensors() }
    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
        rangingCoordinator.close()
        binding.faceBlurOverlay.clearDetections()
        cameraController.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        @Suppress("DEPRECATION")
        cameraController.updateRotation(windowManager.defaultDisplay.rotation)
        hideSystemBars()
        binding.hudTop.post { updateFaceStatsPosition() }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        cameraController.start(windowManager.defaultDisplay.rotation)
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startAsController() {
        if (!hasAllPermissions()) return
        setButtonsEnabled(false)
        rangingCoordinator.startController()
    }

    private fun startAsControlee() {
        if (!hasAllPermissions()) return
        setButtonsEnabled(false)
        rangingCoordinator.startControlee()
    }

    private fun renderTelemetry(d: UwbRangingManager.RangingData) {
        binding.tvDistance.text = String.format("%.2f m", d.distanceMeters)
        binding.tvAzimuth.text = String.format("%.1f°", d.azimuthDegrees)
        binding.tvElevation.text = String.format("%.1f°", d.elevationDegrees)
        if (STAGE_2) {
            binding.arOverlay.updatePosition(d.distanceMeters, d.azimuthDegrees, d.elevationDegrees)
            binding.radarView.updatePosition(d.distanceMeters, d.azimuthDegrees)
        }
    }

    private fun updateStatus(m: String) { binding.tvStatus.text = m; Log.d(TAG, m) }
    private fun showTelemetry(on: Boolean) {
        binding.llTelemetry.visibility = if (on) View.VISIBLE else View.GONE
        binding.hudTop.post { updateFaceStatsPosition() }
    }

    private fun updateFaceStatsPosition() {
        binding.faceBlurOverlay.setStatsTopInset(binding.hudTop.bottom.toFloat())
    }
    private fun setButtonsEnabled(on: Boolean) {
        binding.btnController.isEnabled = on; binding.btnControlee.isEnabled = on
        binding.llButtons.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnStop.visibility = if (on) View.GONE else View.VISIBLE
    }
    private fun clearPeerTelemetry() {
        if (STAGE_2) { binding.arOverlay.clearPeer(); binding.radarView.clearPeer() }
        binding.tvDistance.text = "--.- m"; binding.tvAzimuth.text = "--.-°"; binding.tvElevation.text = "--.-°"
    }
    private fun stopEverything() {
        rangingCoordinator.stop()
        clearPeerTelemetry()
        showTelemetry(false); setButtonsEnabled(true); updateStatus("Stopped. Select a role.")
    }
}
