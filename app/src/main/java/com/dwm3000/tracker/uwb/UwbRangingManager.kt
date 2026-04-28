package com.dwm3000.tracker.uwb

import android.content.Context
import android.util.Log
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControleeSessionScope
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Manages FiRa-compliant UWB ranging sessions using the AndroidX UWB library.
 *
 * Supports both Controller and Controlee roles:
 * - Controller: Initiates ranging (the device that ran BLE Central / created session params).
 * - Controlee: Responds to ranging (the device that ran BLE Peripheral / received session params).
 *
 * The AndroidX UWB library handles FiRa compliance internally via the system UWB service
 * on Pixel 8 Pro / Pixel 9 Pro XL.
 */
class UwbRangingManager(private val context: Context) {

    companion object {
        private const val TAG = "UwbRangingManager"
    }

    private var rangingJob: Job? = null
    private var controllerSessionScope: UwbControllerSessionScope? = null
    private var controleeSessionScope: UwbControleeSessionScope? = null

    data class RangingData(
        val distanceMeters: Float,
        val azimuthDegrees: Float,
        val elevationDegrees: Float,
        val peerAddress: String
    )

    /** Info obtained from the UWB system, needed before BLE exchange. */
    data class UwbScopeInfo(
        val channel: Int,
        val preambleIndex: Int,
        val localAddress: ByteArray  // System-assigned 2-byte UWB address
    )

    /**
     * Creates the Controller session scope to obtain the system-assigned complex channel
     * and local UWB address. Must be called BEFORE the BLE exchange.
     */
    suspend fun prepareControllerScope(): UwbScopeInfo {
        val uwbManager = UwbManager.createInstance(context)
        val scope = uwbManager.controllerSessionScope()
        controllerSessionScope = scope

        val addrBytes = scope.localAddress.address

        Log.d(TAG, "Controller session scope created")
        Log.d(TAG, "  Local UWB address: ${addrBytes.toHex()}")
        Log.d(TAG, "  UWB complex channel: ${scope.uwbComplexChannel}")

        return UwbScopeInfo(
            channel = scope.uwbComplexChannel.channel,
            preambleIndex = scope.uwbComplexChannel.preambleIndex,
            localAddress = addrBytes
        )
    }

    /**
     * Creates the Controlee session scope to obtain the system-assigned local UWB address.
     * Must be called BEFORE the BLE exchange so the real address can be advertised.
     */
    suspend fun prepareControleeScope(): UwbScopeInfo {
        val uwbManager = UwbManager.createInstance(context)
        val scope = uwbManager.controleeSessionScope()
        controleeSessionScope = scope

        val addrBytes = scope.localAddress.address

        Log.d(TAG, "Controlee session scope created")
        Log.d(TAG, "  Local UWB address: ${addrBytes.toHex()}")

        return UwbScopeInfo(
            channel = 0,  // Controlee gets channel from Controller via BLE
            preambleIndex = 0,
            localAddress = addrBytes
        )
    }

    /**
     * Start ranging as Controller using the previously prepared session scope.
     * Call prepareControllerScope() first.
     */
    fun startAsController(
        scope: CoroutineScope,
        sessionId: Int,
        sessionKey: ByteArray,
        peerAddress: ByteArray,
        onResult: (RangingData) -> Unit,
        onError: (String) -> Unit,
        onPeerDisconnected: () -> Unit
    ) {
        val ctrlScope = controllerSessionScope
        if (ctrlScope == null) {
            onError("Controller scope not prepared. Call prepareControllerScope() first.")
            return
        }

        rangingJob = scope.launch(Dispatchers.Main) {
            try {
                val peerUwbAddress = UwbAddress(peerAddress)
                val peerDevices = listOf(UwbDevice(peerUwbAddress))

                val rangingParams = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
                    sessionId = sessionId,
                    subSessionId = 0,
                    sessionKeyInfo = sessionKey,
                    subSessionKeyInfo = null,
                    complexChannel = ctrlScope.uwbComplexChannel,
                    peerDevices = peerDevices,
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                )

                Log.d(TAG, "Starting Controller ranging session...")
                collectRangingResults(ctrlScope.prepareSession(rangingParams), true, onResult, onError, onPeerDisconnected)
            } catch (e: Exception) {
                Log.e(TAG, "Controller ranging failed", e)
                onError("Controller error: ${e.message}")
            }
        }
    }

    /**
     * Start ranging as Controlee using the previously prepared session scope.
     * Call prepareControleeScope() first.
     * Uses the channel/preamble received from the Controller via BLE.
     */
    fun startAsControlee(
        scope: CoroutineScope,
        sessionId: Int,
        channel: Int,
        preambleIndex: Int,
        sessionKey: ByteArray,
        controllerAddress: ByteArray,
        onResult: (RangingData) -> Unit,
        onError: (String) -> Unit,
        onPeerDisconnected: () -> Unit
    ) {
        val ctrleeScope = controleeSessionScope
        if (ctrleeScope == null) {
            onError("Controlee scope not prepared. Call prepareControleeScope() first.")
            return
        }

        rangingJob = scope.launch(Dispatchers.Main) {
            try {
                val controllerUwbAddress = UwbAddress(controllerAddress)
                val controllerDevice = UwbDevice(controllerUwbAddress)

                val complexChannel = UwbComplexChannel(
                    channel = channel,
                    preambleIndex = preambleIndex
                )

                Log.d(TAG, "Controlee ranging: peer=${controllerAddress.toHex()}, ch=$channel, preamble=$preambleIndex")

                val rangingParams = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
                    sessionId = sessionId,
                    subSessionId = 0,
                    sessionKeyInfo = sessionKey,
                    subSessionKeyInfo = null,
                    complexChannel = complexChannel,
                    peerDevices = listOf(controllerDevice),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                )

                Log.d(TAG, "Starting Controlee ranging session (ch=$channel, preamble=$preambleIndex)...")
                collectRangingResults(ctrleeScope.prepareSession(rangingParams), true, onResult, onError, onPeerDisconnected)
            } catch (e: Exception) {
                Log.e(TAG, "Controlee ranging failed", e)
                onError("Controlee error: ${e.message}")
            }
        }
    }

    private suspend fun collectRangingResults(
        rangingFlow: kotlinx.coroutines.flow.Flow<RangingResult>,
        negateAoA: Boolean,
        onResult: (RangingData) -> Unit,
        onError: (String) -> Unit,
        onPeerDisconnected: () -> Unit
    ) {
        rangingFlow
            .onEach { result ->
                when (result) {
                    is RangingResult.RangingResultPosition -> {
                        val position = result.position
                        val distance = position.distance?.value ?: 0f
                        val rawAz = position.azimuth?.value ?: 0f
                        val rawEl = position.elevation?.value ?: 0f
                        val addr = result.device.address.toString()

                        val sign = if (negateAoA) -1f else 1f
                        val azimuth = rawAz * sign
                        val elevation = rawEl * sign

                        Log.d(TAG, "Range: ${distance}m, Az: ${azimuth}° (raw:${rawAz}°), El: ${elevation}° (negate=$negateAoA)")

                        onResult(
                            RangingData(
                                distanceMeters = distance,
                                azimuthDegrees = azimuth,
                                elevationDegrees = elevation,
                                peerAddress = addr
                            )
                        )
                    }
                    is RangingResult.RangingResultPeerDisconnected -> {
                        Log.w(TAG, "Peer disconnected: ${result.device.address}")
                        onPeerDisconnected()
                    }
                }
            }
            .catch { e ->
                Log.e(TAG, "Ranging flow error", e)
                onError("Ranging error: ${e.message}")
            }
            .collect {} // Terminal operator to start the flow
    }

    fun stopRanging() {
        rangingJob?.cancel()
        rangingJob = null
        controllerSessionScope = null
        controleeSessionScope = null
        Log.d(TAG, "Ranging stopped")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }