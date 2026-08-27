package com.ecohimpribor.ecogdmobile.ble

import java.util.UUID

object BleUartConstants {
    val SERVICE_UUID: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
    val CHARACTERISTIC_TX_UUID: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
    val CHARACTERISTIC_RX_ALT_UUID: UUID = UUID.fromString("49535343-4c8a-39b3-2f49-511cff073b7e")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
