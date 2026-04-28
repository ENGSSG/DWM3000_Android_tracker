package com.dwm3000.tracker.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.dwm3000.tracker.UwbSessionParams

/**
 * BLE Central (GATT Client) role — used by the "Controller" device.
 *
 * Scans for a peripheral advertising the UWB OOB service, connects, then:
 * 1. Subscribes to notifications on the Response Characteristic (FFF2).
 * 2. Writes session params (session ID, channel, preamble, local address) to FFF1.
 * 3. Receives the Controlee's UWB address via notification on FFF2.
 * 4. Invokes callback with all params needed to start UWB ranging.
 */
class BleCentral(
    private val context: Context,
    private val sessionId: Int,
    private val channel: Int,
    private val preambleIndex: Int,
    private val sessionKey: ByteArray,
    private val localAddress: ByteArray,
    private val onControleeFound: (controleeAddress: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "BleCentral"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        startScan()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun stop() {
        if (scanning) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            scanning = false
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
        Log.d(TAG, "Central stopped")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE Scanner not available")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.UWB_OOB_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "Scanning for UWB OOB peripheral...")
    }

    private val scanCallback = object : ScanCallback() {
        @android.annotation.SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            Log.d(TAG, "Found peripheral: ${result.device.address}")

            // Stop scanning once we find our peer
            bluetoothAdapter.bluetoothLeScanner?.stopScan(this)
            scanning = false

            connectToPeripheral(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            scanning = false
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun connectToPeripheral(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address}")

        bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to GATT server, discovering services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from GATT server")
                }
            }

            @Suppress("DEPRECATION")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed: $status")
                    return
                }

                val service = gatt.getService(BleConstants.UWB_OOB_SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "UWB OOB service not found")
                    return
                }

                // Step 1: Subscribe to notifications on the response characteristic
                val responseChar = service.getCharacteristic(BleConstants.UWB_RESPONSE_CHAR_UUID)
                if (responseChar == null) {
                    Log.e(TAG, "Response characteristic not found")
                    return
                }
                gatt.setCharacteristicNotification(responseChar, true)

                val cccd = responseChar.getDescriptor(BleConstants.CCCD_UUID)
                cccd?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                Log.d(TAG, "Subscribed to response notifications")
            }

            @Suppress("DEPRECATION")
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == BleConstants.CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                    // Step 2: Now write session params to the controlee
                    val service = gatt.getService(BleConstants.UWB_OOB_SERVICE_UUID)
                    val paramsChar = service?.getCharacteristic(BleConstants.UWB_SESSION_PARAMS_CHAR_UUID)
                    if (paramsChar != null) {
                        val payload = UwbSessionParams.serialize(
                            sessionId,
                            channel,
                            preambleIndex,
                            sessionKey,
                            localAddress
                        )
                        paramsChar.value = payload
                        paramsChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        gatt.writeCharacteristic(paramsChar)
                        Log.d(TAG, "Wrote session params to controlee (${payload.size} bytes)")
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == BleConstants.UWB_RESPONSE_CHAR_UUID) {
                    val controleeAddress = characteristic.value
                    Log.d(TAG, "Received controlee address: ${controleeAddress.toHex()} (${controleeAddress.size} bytes)")

                    // We have the controlee's UWB address — OOB exchange complete
                    onControleeFound(controleeAddress)
                }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
