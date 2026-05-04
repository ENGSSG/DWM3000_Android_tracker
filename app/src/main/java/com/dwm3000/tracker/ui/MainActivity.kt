package com.dwm3000.tracker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.dwm3000.tracker.UwbSessionParams
import com.dwm3000.tracker.ble.BleCentral
import com.dwm3000.tracker.ble.BlePeripheral
import com.dwm3000.tracker.databinding.ActivityMainBinding
import com.dwm3000.tracker.uwb.UwbRangingManager
import com.dwm3000.tracker.vision.FaceAnalysis
import com.dwm3000.tracker.vision.GyroImageMotionSensor
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // Stage 2: camera preview + AR reticle + radar enabled.
        private const val STAGE_2 = true
        private const val FACE_DETECTOR_INTERVAL_MS = 200L
    }

    private lateinit var binding: ActivityMainBinding
    private var uwbRangingManager: UwbRangingManager? = null
    private var bleCentral: BleCentral? = null
    private var blePeripheral: BlePeripheral? = null
    private var cameraPreview: Preview? = null
    private var cameraAnalysis: ImageAnalysis? = null
    private lateinit var cameraAnalysisExecutor: ExecutorService
    private var gravitySensor: GravityRollSensor? = null
    private lateinit var imageMotionSensor: GyroImageMotionSensor
    private lateinit var uwbImuFusion: UwbImuFusionManager

    private val sessionId = UwbSessionParams.generateSessionId()
    private val sessionKey = UwbSessionParams.generateSessionKey()

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
        cameraAnalysisExecutor = Executors.newSingleThreadExecutor()
        imageMotionSensor = GyroImageMotionSensor(this)
        uwbImuFusion = UwbImuFusionManager(this) { fused ->
            renderTelemetry(fused)
        }

        uwbRangingManager = UwbRangingManager(this)

        if (STAGE_2) {
            CameraFovHelper.getRearCameraFov(this)?.let {
                Log.d(TAG, "Camera FoV: H=${it.horizontalDeg} V=${it.verticalDeg}")
                binding.arOverlay.mapper.setCameraFov(it.horizontalDeg, it.verticalDeg)
                imageMotionSensor.setCameraFov(it.horizontalDeg, it.verticalDeg)
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

    override fun onResume() { super.onResume(); gravitySensor?.start(); imageMotionSensor.start(); uwbImuFusion.start() }
    override fun onPause() { super.onPause(); gravitySensor?.stop(); imageMotionSensor.stop(); uwbImuFusion.stop() }
    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
        binding.faceBlurOverlay.clearDetections()
        cameraAnalysisExecutor.shutdown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        @Suppress("DEPRECATION")
        cameraPreview?.targetRotation = windowManager.defaultDisplay.rotation
        @Suppress("DEPRECATION")
        cameraAnalysis?.targetRotation = windowManager.defaultDisplay.rotation
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val prov = f.get()
            val preview = Preview.Builder()
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build().also { it.setSurfaceProvider(binding.cameraPreview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        cameraAnalysisExecutor,
                        FaceAnalysis(this, imageMotionSensor, FACE_DETECTOR_INTERVAL_MS) { bitmap, faces, stats ->
                            binding.faceBlurOverlay.post {
                                binding.faceBlurOverlay.updateDetections(bitmap, faces, stats)
                            }
                        }
                    )
                }
            cameraPreview = preview
            cameraAnalysis = analysis
            try { prov.unbindAll(); prov.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
            catch (e: Exception) { Log.e(TAG, "Camera failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startAsController() {
        if (!hasAllPermissions()) return
        setButtonsEnabled(false); updateStatus("Controller: Preparing UWB...")
        lifecycleScope.launch {
            try {
                val s = uwbRangingManager!!.prepareControllerScope()
                updateStatus("Controller: Scanning BLE...")
                bleCentral = BleCentral(this@MainActivity, sessionId, s.channel, s.preambleIndex, sessionKey, s.localAddress) { addr ->
                    runOnUiThread { updateStatus("Starting UWB..."); bleCentral?.stop(); startUwbController(addr) }
                }
                bleCentral?.start()
            } catch (e: Exception) { Log.e(TAG, "Err", e); updateStatus("Error: ${e.message}"); setButtonsEnabled(true) }
        }
    }

    private fun startUwbController(addr: ByteArray) {
        updateStatus("Ranging active"); showTelemetry(true)
        uwbRangingManager?.startAsController(lifecycleScope, sessionId, sessionKey, addr,
            { d -> runOnUiThread { updateTelemetry(d) } },
            { m -> runOnUiThread { updateStatus("Error: $m") } },
            { runOnUiThread { onPeerLost() } })
    }

    private fun startAsControlee() {
        if (!hasAllPermissions()) return
        setButtonsEnabled(false); updateStatus("Controlee: Preparing UWB...")
        lifecycleScope.launch {
            try {
                val s = uwbRangingManager!!.prepareControleeScope()
                updateStatus("Controlee: Advertising BLE...")
                blePeripheral = BlePeripheral(this@MainActivity, s.localAddress) { sid, ch, pre, key, addr ->
                    runOnUiThread { updateStatus("Starting UWB..."); blePeripheral?.stop(); startUwbControlee(sid, ch, pre, key, addr) }
                }
                blePeripheral?.start()
            } catch (e: Exception) { Log.e(TAG, "Err", e); updateStatus("Error: ${e.message}"); setButtonsEnabled(true) }
        }
    }

    private fun startUwbControlee(sid: Int, ch: Int, pre: Int, key: ByteArray, addr: ByteArray) {
        updateStatus("Ranging active"); showTelemetry(true)
        uwbRangingManager?.startAsControlee(lifecycleScope, sid, ch, pre, key, addr,
            { d -> runOnUiThread { updateTelemetry(d) } },
            { m -> runOnUiThread { updateStatus("Error: $m") } },
            { runOnUiThread { onPeerLost() } })
    }

    private fun updateTelemetry(d: UwbRangingManager.RangingData) {
        renderTelemetry(uwbImuFusion.processUwb(d))
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
    private fun showTelemetry(on: Boolean) { binding.llTelemetry.visibility = if (on) View.VISIBLE else View.GONE }
    private fun setButtonsEnabled(on: Boolean) {
        binding.btnController.isEnabled = on; binding.btnControlee.isEnabled = on
        binding.llButtons.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnStop.visibility = if (on) View.GONE else View.VISIBLE
    }
    private fun onPeerLost() {
        updateStatus("Peer disconnected")
        uwbImuFusion.reset()
        if (STAGE_2) { binding.arOverlay.clearPeer(); binding.radarView.clearPeer() }
        binding.tvDistance.text = "--.- m"; binding.tvAzimuth.text = "--.-°"; binding.tvElevation.text = "--.-°"
    }
    private fun stopEverything() {
        bleCentral?.stop(); blePeripheral?.stop(); uwbRangingManager?.stopRanging()
        bleCentral = null; blePeripheral = null
        uwbImuFusion.reset()
        if (STAGE_2) { binding.arOverlay.clearPeer(); binding.radarView.clearPeer() }
        binding.tvDistance.text = "--.- m"; binding.tvAzimuth.text = "--.-°"; binding.tvElevation.text = "--.-°"
        showTelemetry(false); setButtonsEnabled(true); updateStatus("Stopped. Select a role.")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
