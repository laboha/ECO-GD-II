package com.ecohimpribor.ecogdmobile.ui

/** Запись скорости/порогов на устройство. onResult(success, reason) — reason заполнен только при ошибке. */
interface ParametersWriteHost {
    fun writeBaudRateToDevice(baudRate: Int, onResult: (Boolean, String) -> Unit)

    fun writeThresholdsToDevice(threshold1: Float, threshold2: Float, onResult: (Boolean, String) -> Unit)

    fun requestParametersRead()

    /** Запрашивает реальный текущий пароль записи прямо с устройства (адрес 200). */
    fun requestStoredPasswordRead()
}