package com.ecohimpribor.ecogdmobile.ble

object LatestReading {
    var isConnected: Boolean = false
    var statusLabel: String = ""
    var concentration: Float = 0f
    var range: Float = 100f
    var faultCode: String? = null
    var gasCode: Int? = null
    var unitsCode: Int? = null
    var modbusId: Int? = null
    var baudRate: Int? = null
    var threshold1: Float? = null
    var threshold2: Float? = null
    var writePassword: Int? = null
    var isWritePasswordConfirmed: Boolean = false

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    //  Сброс к значениям по умолчанию
    fun reset() {
        isConnected = false
        statusLabel = ""
        concentration = 0f
        range = 100f
        faultCode = null
        gasCode = null
        unitsCode = null
        modbusId = null
        baudRate = null
        threshold1 = null
        threshold2 = null
    }
}