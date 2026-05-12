package com.dwm3000.tracker.ranging

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dwm3000.tracker.UwbSessionParams
import com.dwm3000.tracker.ble.BleCentral
import com.dwm3000.tracker.ble.BlePeripheral
import com.dwm3000.tracker.fusion.UwbImuFusionManager
import com.dwm3000.tracker.uwb.UwbRangingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class RangingSessionCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val onStatusChanged: (String) -> Unit,
    private val onRangingStarted: () -> Unit,
    private val onRangingUpdate: (UwbRangingManager.RangingData) -> Unit,
    private val onStartFailed: () -> Unit,
    private val onPeerLost: () -> Unit
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uwbRangingManager = UwbRangingManager(appContext)
    private val fusionManager = UwbImuFusionManager(appContext) { fused ->
        dispatchToMain { onRangingUpdate(fused) }
    }

    private var bleCentral: BleCentral? = null
    private var blePeripheral: BlePeripheral? = null
    private var closed = false
    private var startGeneration = 0L

    private val sessionId = UwbSessionParams.generateSessionId()
    private val sessionKey = UwbSessionParams.generateSessionKey()

    fun startSensors() {
        fusionManager.start()
    }

    fun stopSensors() {
        fusionManager.stop()
    }

    fun startController() {
        val generation = nextStartGeneration()
        dispatchToMain { onStatusChanged("Controller: Preparing UWB...") }
        scope.launch {
            try {
                val sessionScope = uwbRangingManager.prepareControllerScope()
                if (!isCurrentStart(generation)) return@launch

                dispatchToMain { onStatusChanged("Controller: Scanning BLE...") }
                bleCentral?.stop()
                bleCentral = BleCentral(
                    appContext,
                    sessionId,
                    sessionScope.channel,
                    sessionScope.preambleIndex,
                    sessionKey,
                    sessionScope.localAddress
                ) { controleeAddress ->
                    dispatchToMain {
                        if (!isCurrentStart(generation)) return@dispatchToMain
                        onStatusChanged("Starting UWB...")
                        bleCentral?.stop()
                        startUwbController(generation, controleeAddress)
                    }
                }
                if (isCurrentStart(generation)) {
                    bleCentral?.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Controller start failed", e)
                if (isCurrentStart(generation)) {
                    dispatchToMain {
                        onStatusChanged("Error: ${e.message}")
                        onStartFailed()
                    }
                }
            }
        }
    }

    fun startControlee() {
        val generation = nextStartGeneration()
        dispatchToMain { onStatusChanged("Controlee: Preparing UWB...") }
        scope.launch {
            try {
                val sessionScope = uwbRangingManager.prepareControleeScope()
                if (!isCurrentStart(generation)) return@launch

                dispatchToMain { onStatusChanged("Controlee: Advertising BLE...") }
                blePeripheral?.stop()
                blePeripheral = BlePeripheral(appContext, sessionScope.localAddress) {
                        receivedSessionId,
                        channel,
                        preambleIndex,
                        receivedSessionKey,
                        controllerAddress ->
                    dispatchToMain {
                        if (!isCurrentStart(generation)) return@dispatchToMain
                        onStatusChanged("Starting UWB...")
                        blePeripheral?.stop()
                        startUwbControlee(
                            generation,
                            receivedSessionId,
                            channel,
                            preambleIndex,
                            receivedSessionKey,
                            controllerAddress
                        )
                    }
                }
                if (isCurrentStart(generation)) {
                    blePeripheral?.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Controlee start failed", e)
                if (isCurrentStart(generation)) {
                    dispatchToMain {
                        onStatusChanged("Error: ${e.message}")
                        onStartFailed()
                    }
                }
            }
        }
    }

    fun stop() {
        startGeneration += 1L
        bleCentral?.stop()
        blePeripheral?.stop()
        uwbRangingManager.stopRanging()
        bleCentral = null
        blePeripheral = null
        fusionManager.reset()
    }

    override fun close() {
        closed = true
        stop()
        stopSensors()
    }

    private fun startUwbController(generation: Long, controleeAddress: ByteArray) {
        if (!isCurrentStart(generation)) return

        onStatusChanged("Ranging active")
        onRangingStarted()
        uwbRangingManager.startAsController(
            scope,
            sessionId,
            sessionKey,
            controleeAddress,
            { raw ->
                dispatchToMain {
                    if (isCurrentStart(generation)) {
                        onRangingUpdate(fusionManager.processUwb(raw))
                    }
                }
            },
            { message ->
                dispatchToMain {
                    if (isCurrentStart(generation)) onStatusChanged("Error: $message")
                }
            },
            {
                dispatchToMain {
                    if (isCurrentStart(generation)) handlePeerLost()
                }
            }
        )
    }

    private fun startUwbControlee(
        generation: Long,
        receivedSessionId: Int,
        channel: Int,
        preambleIndex: Int,
        receivedSessionKey: ByteArray,
        controllerAddress: ByteArray
    ) {
        if (!isCurrentStart(generation)) return

        onStatusChanged("Ranging active")
        onRangingStarted()
        uwbRangingManager.startAsControlee(
            scope,
            receivedSessionId,
            channel,
            preambleIndex,
            receivedSessionKey,
            controllerAddress,
            { raw ->
                dispatchToMain {
                    if (isCurrentStart(generation)) {
                        onRangingUpdate(fusionManager.processUwb(raw))
                    }
                }
            },
            { message ->
                dispatchToMain {
                    if (isCurrentStart(generation)) onStatusChanged("Error: $message")
                }
            },
            {
                dispatchToMain {
                    if (isCurrentStart(generation)) handlePeerLost()
                }
            }
        )
    }

    private fun handlePeerLost() {
        onStatusChanged("Peer disconnected")
        fusionManager.reset()
        onPeerLost()
    }

    private fun dispatchToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun nextStartGeneration(): Long {
        closed = false
        startGeneration += 1L
        return startGeneration
    }

    private fun isCurrentStart(generation: Long): Boolean {
        return !closed && generation == startGeneration
    }

    private companion object {
        private const val TAG = "RangingSessionCoordinator"
    }
}
