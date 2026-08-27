package com.ecohimpribor.ecogdmobile.ui

/** Реализуется MainActivity — хранит реальный Modbus-адрес устройства
 *  (slave ID), с которым ведётся обмен по BLE (см. BleModbusManager.setSlaveId()). */
interface ModbusIdHost {
    fun getModbusId(): Int
    fun setModbusId(id: Int)
}