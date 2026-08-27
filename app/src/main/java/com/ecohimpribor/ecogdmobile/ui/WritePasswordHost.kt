package com.ecohimpribor.ecogdmobile.ui

/**
 * Пароль записи (регистр-разблокировка 0xFFFF — см. ble/ModbusRtu.kt:
 * REG_WRITE_UNLOCK). Это НЕ значение с устройства — локальная настройка
 * приложения, подставляется автоматически перед каждой попыткой записи.
 * Значение по умолчанию (123456) не подтверждено документацией — можно
 * подобрать вручную через UI или прочитать реальное с устройства (адрес 200).
 */
interface WritePasswordHost {
    fun getWritePassword(): Int
    fun setWritePassword(password: Int)
}