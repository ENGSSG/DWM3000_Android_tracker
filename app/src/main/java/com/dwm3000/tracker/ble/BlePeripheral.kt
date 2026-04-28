package com.dwm3000.tracker.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.dwm3000.tracker.UwbSessionParams


/**
 * BLE Peripheral (GATT Server) role — used by the "Controlee" device.
 *
 * Advertises the UWB OOB service, hosts a GATT server with two characteristics:
 * 1. Session Params Characteristic: the Controller writes its session params here.
 * 2. Response Characteristic: we write our local UWB address back for the Controller to read/notify.
 *
 * Flow:
 * - Controlee starts advertising.
 * - Controller scans, connects, writes session params to FFF1.
 * - Controlee reads params, writes its own address to FFF2 (notification).
 * - Both devices now have all params needed to start UWB ranging.
 */
class BlePeripheral(
    private val context: Context,
    private val uwbAddress: ByteArray,  // System-assigned UWB address to send to Controller
    private val onParamsReceived: (sessionId: Int, channel: Int, preambleIndex: Int, sessionKey: ByteArray, controllerAddress: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "BlePeripheral"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var connectedDevice: BluetoothDevice? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        startGattServer()
        startAdvertising()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun stop() {
        bluetoothAdapter.bluetoothLeAdvertiser?.let { advertiser ->
            advertiseCallback?.let { advertiser.stopAdvertising(it) }
        }
        gattServer?.close()
        gattServer = null
        Log.d(TAG, "Peripheral stopped")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGattServer() {
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    connectedDevice = device
                    Log.d(TAG, "Controller connected: ${device.address}")
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    connectedDevice = null
                    Log.d(TAG, "Controller disconnected")
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                when (characteristic.uuid) {
                    BleConstants.UWB_SESSION_PARAMS_CHAR_UUID -> {
                        Log.d(TAG, "Received session params from controller (${value.size} bytes)")

                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }

                        // Parse the controller's session params
                        val params = UwbSessionParams.deserialize(value)
                        Log.d(TAG, "Session ID=${params.sessionId}, Ch=${params.channel}, Preamble=${params.preambleIndex}, Controller addr=${params.address.toHex()}")

                        // Write our local address to the response characteristic so Controller can read it
                        val responseChar = gattServer?.getService(BleConstants.UWB_OOB_SERVICE_UUID)
                            ?.getCharacteristic(BleConstants.UWB_RESPONSE_CHAR_UUID)
                        responseChar?.let {
                            it.value = uwbAddress
                            gattServer?.notifyCharacteristicChanged(device, it, false)
                            Log.d(TAG, "Sent UWB address ${uwbAddress.toHex()} to controller")
                        }

                        onParamsReceived(params.sessionId, params.channel, params.preambleIndex, params.sessionKey, params.address)
                    }
                    else -> {
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        }
                    }
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                when (characteristic.uuid) {
                    BleConstants.UWB_RESPONSE_CHAR_UUID -> {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, uwbAddress)
                    }
                    else -> {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (descriptor.uuid == BleConstants.CCCD_UUID) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
        }

        gattServer = bluetoothManager.openGattServer(context, serverCallback)

        val service = BluetoothGattService(
            BleConstants.UWB_OOB_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Characteristic for receiving session params from Controller
        val sessionParamsChar = BluetoothGattCharacteristic(
            BleConstants.UWB_SESSION_PARAMS_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Characteristic for sending our address back to Controller
        val responseChar = BluetoothGattCharacteristic(
            BleConstants.UWB_RESPONSE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        )
        responseChar.addDescriptor(cccd)

        service.addCharacteristic(sessionParamsChar)
        service.addCharacteristic(responseChar)

        gattServer?.addService(service)
        Log.d(TAG, "GATT server started")
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE Advertising not supported")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.UWB_OOB_SERVICE_UUID))
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "Advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed with error code: $errorCode")
            }
        }

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
