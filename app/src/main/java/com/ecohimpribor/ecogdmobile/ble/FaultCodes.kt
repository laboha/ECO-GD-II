package com.ecohimpribor.ecogdmobile.ble

object FaultCodes {

    data class Fault(val code: String, val description: String)

    private val TABLE: Map<Int, Fault> = mapOf(
        512 to Fault("E01", "Нет опт. сенсора"),
        520 to Fault("E01", "Нет опт. сенсора"),
        12288 to Fault("E02", "Нет ПГУ"),
        128 to Fault("E03", "Нет нуля"),
        1032 to Fault("E04", "Нет эх. сенсора")
    )

    fun lookup(statusCode: Int): Fault? = TABLE[statusCode]
}