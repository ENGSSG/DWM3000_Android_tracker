package com.example.pixeluwb.ble

import java.util.UUID

/**
 * BLE GATT service and characteristic UUIDs for the UWB OOB parameter exchange.
 * Both devices use the same UUIDs to discover each other and exchange session params.
 */
object BleConstants {
    // Custom service UUID for UWB OOB exchange
    val UWB_OOB_SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")

    // Characteristic: Controller writes its session params here
    val UWB_SESSION_PARAMS_CHAR_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")

    // Characteristic: Controlee writes its address response here
    val UWB_RESPONSE_CHAR_UUID: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")

    // Standard Client Characteristic Configuration Descriptor
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}
