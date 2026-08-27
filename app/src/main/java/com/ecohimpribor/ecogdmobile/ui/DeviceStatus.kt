package com.ecohimpribor.ecogdmobile.ui

import com.ecohimpribor.ecogdmobile.R
import com.ecohimpribor.ecogdmobile.ble.FaultCodes
import com.ecohimpribor.ecogdmobile.ble.ModbusRtu

/**
 * Статус показаний прибора — определяет иконку/надпись под названием газа
 * и цвет светодиода-индикатора. Вычисляется НАПРЯМУЮ из битовой маски
 * реального регистра Status (адрес 13, см. ble/ModbusRtu.kt) — устройство
 * само сообщает, превышен ли порог, идёт ли перегрузка и т.д., поэтому
 * сравнивать концентрацию с локально введёнными порогами не нужно.
 *
 * Цвета светодиода подтверждены официальной документацией ("РЭ_ЭКО-GD",
 * таблица 6): НОРМА — зелёный, ЗАГАЗОВАННОСТЬ (пороги/перегрузка) — красный,
 * НЕИСПРАВНОСТЬ — жёлтый, СЕРВИС (локальный магнитный интерфейс, в
 * приложении не используется) — синий.
 */
enum class DeviceStatus(
    val label: String,
    val iconRes: Int,
    val ledMode: LedMode
) {
    INIT("Инициализация", R.drawable.ic_status_check, LedMode.INIT),
    NORMAL("Норма", R.drawable.ic_status_check, LedMode.NORMAL),
    THRESHOLD_1("Порог 1", R.drawable.ic_status_bell, LedMode.THRESHOLD_1),
    THRESHOLD_2("Порог 2", R.drawable.ic_status_bell, LedMode.THRESHOLD_2),
    SATURATION("Насыщение", R.drawable.ic_status_check, LedMode.MAX_GAS),
    FAULT("Неисправность", R.drawable.ic_status_warning, LedMode.FAULT);

    companion object {
        private fun bit(mask: Int, n: Int): Boolean = (mask shr n) and 1 == 1

        /**
         * @param statusBitmask сырое значение регистра Status (адрес 13)
         * @param fault расшифровка кода неисправности, если regs[13] совпал
         *   с одним из кодов таблицы Е01..Е04 (см. FaultCodes.lookup())
         */
        fun from(statusBitmask: Int, fault: FaultCodes.Fault?): DeviceStatus {
            if (bit(statusBitmask, ModbusRtu.STATUS_BIT_INIT)) return INIT
            if (fault != null || !bit(statusBitmask, ModbusRtu.STATUS_BIT_HEALTHY)) return FAULT
            if (bit(statusBitmask, ModbusRtu.STATUS_BIT_OVERLOAD)) return SATURATION
            if (bit(statusBitmask, ModbusRtu.STATUS_BIT_THRESHOLD_2)) return THRESHOLD_2
            if (bit(statusBitmask, ModbusRtu.STATUS_BIT_THRESHOLD_1)) return THRESHOLD_1
            return NORMAL
        }
    }
}