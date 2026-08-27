package com.ecohimpribor.ecogdmobile.ble

object ModbusRtu {

    const val FUNC_READ_HOLDING_REGISTERS: Int = 0x03
    const val FUNC_READ_INPUT_REGISTERS: Int = 0x04
    const val FUNC_WRITE_SINGLE_REGISTER: Int = 0x06
    const val FUNC_WRITE_MULTIPLE_REGISTERS: Int = 0x10

    const val REG_GAS: Int = 11
    const val REG_UNITS: Int = 12
    const val REG_STATUS: Int = 13
    const val REG_RANGE: Int = 14
    const val REG_CONC: Int = 16

    const val LIVE_BLOCK_START: Int = REG_GAS
    const val LIVE_BLOCK_COUNT: Int = 7

    const val LIVE_OFFSET_GAS: Int = 0
    const val LIVE_OFFSET_UNITS: Int = 1
    const val LIVE_OFFSET_STATUS: Int = 2
    const val LIVE_OFFSET_RANGE_LOW: Int = 3
    const val LIVE_OFFSET_RANGE_HIGH: Int = 4
    const val LIVE_OFFSET_CONC_LOW: Int = 5
    const val LIVE_OFFSET_CONC_HIGH: Int = 6

    // Status
    const val STATUS_BIT_HEALTHY: Int = 0
    const val STATUS_BIT_THRESHOLD_1: Int = 1
    const val STATUS_BIT_THRESHOLD_2: Int = 2
    const val STATUS_BIT_OVERLOAD: Int = 8
    const val STATUS_BIT_INIT: Int = 15

    // Адреса
    const val PARAMS_ID_BLOCK_START: Int = 0        // a_Address
    const val PARAMS_ID_BLOCK_COUNT: Int = 2         // Address(0) + Baudrate(1)
    const val PARAMS_ID_OFFSET_ADDRESS: Int = 0
    const val PARAMS_ID_OFFSET_BAUDRATE: Int = 1

    const val PARAMS_THRESHOLD_BLOCK_START: Int = 168   // a_TR_Thr1
    const val PARAMS_THRESHOLD_BLOCK_COUNT: Int = 4      // Thr1(168-169) + Thr2(170-171)
    const val PARAMS_THRESHOLD_OFFSET_T1_LOW: Int = 0
    const val PARAMS_THRESHOLD_OFFSET_T1_HIGH: Int = 1
    const val PARAMS_THRESHOLD_OFFSET_T2_LOW: Int = 2
    const val PARAMS_THRESHOLD_OFFSET_T2_HIGH: Int = 3

    // Ток и температура пока не используются
    const val REG_CURRENT: Int = 20
    const val REG_TEMPERATURE: Int = 22

    const val REG_WRITE_UNLOCK: Int = 0xFFFF
    const val REG_STORED_PASSWORD: Int = 200
    const val DEFAULT_WRITE_PASSWORD: Int = 123456
    const val REG_TR_BAUDRATE: Int = 160

    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x0001 != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    fun buildReadPacket(slaveId: Int, startAddress: Int, regCount: Int): ByteArray {
        val packet = ByteArray(8)
        packet[0] = slaveId.toByte()
        packet[1] = FUNC_READ_HOLDING_REGISTERS.toByte()
        packet[2] = (startAddress shr 8 and 0xFF).toByte()
        packet[3] = (startAddress and 0xFF).toByte()
        packet[4] = (regCount shr 8 and 0xFF).toByte()
        packet[5] = (regCount and 0xFF).toByte()
        val crc = crc16(packet, 6)
        packet[6] = (crc and 0xFF).toByte()
        packet[7] = (crc shr 8 and 0xFF).toByte()
        return packet
    }

    fun buildWriteSingleRegisterPacket(slaveId: Int, address: Int, value: Int): ByteArray {
        val packet = ByteArray(8)
        packet[0] = slaveId.toByte()
        packet[1] = FUNC_WRITE_SINGLE_REGISTER.toByte()
        packet[2] = (address shr 8 and 0xFF).toByte()
        packet[3] = (address and 0xFF).toByte()
        packet[4] = (value shr 8 and 0xFF).toByte()
        packet[5] = (value and 0xFF).toByte()
        val crc = crc16(packet, 6)
        packet[6] = (crc and 0xFF).toByte()
        packet[7] = (crc shr 8 and 0xFF).toByte()
        return packet
    }

    fun buildWriteMultipleRegistersPacket(slaveId: Int, startAddress: Int, values: IntArray): ByteArray {
        val byteCount = values.size * 2
        val packet = ByteArray(9 + byteCount)
        packet[0] = slaveId.toByte()
        packet[1] = FUNC_WRITE_MULTIPLE_REGISTERS.toByte()
        packet[2] = (startAddress shr 8 and 0xFF).toByte()
        packet[3] = (startAddress and 0xFF).toByte()
        packet[4] = (values.size shr 8 and 0xFF).toByte()
        packet[5] = (values.size and 0xFF).toByte()
        packet[6] = byteCount.toByte()
        for (i in values.indices) {
            packet[7 + i * 2] = (values[i] shr 8 and 0xFF).toByte()
            packet[7 + i * 2 + 1] = (values[i] and 0xFF).toByte()
        }
        val crc = crc16(packet, 7 + byteCount)
        packet[7 + byteCount] = (crc and 0xFF).toByte()
        packet[7 + byteCount + 1] = (crc shr 8 and 0xFF).toByte()
        return packet
    }

    data class ReadResponse(
        val slaveId: Int,
        val functionCode: Int,
        val registers: IntArray,
        val isError: Boolean = false,
        val errorCode: Int = 0
    )

    fun parseReadResponse(frame: ByteArray): ReadResponse? {
        if (frame.size < 5) return null
        val slaveId = frame[0].toInt() and 0xFF
        val funcRaw = frame[1].toInt() and 0xFF

        if (funcRaw and 0x80 != 0) {
            if (frame.size < 5) return null
            val errorCode = frame[2].toInt() and 0xFF
            return ReadResponse(slaveId, funcRaw and 0x7F, IntArray(0), isError = true, errorCode = errorCode)
        }

        if (funcRaw != FUNC_READ_HOLDING_REGISTERS && funcRaw != FUNC_READ_INPUT_REGISTERS) return null
        val byteCount = frame[2].toInt() and 0xFF
        val expectedLen = 3 + byteCount + 2
        if (frame.size < expectedLen) return null

        val crcReceived = (frame[expectedLen - 2].toInt() and 0xFF) or ((frame[expectedLen - 1].toInt() and 0xFF) shl 8)
        val crcCalc = crc16(frame, expectedLen - 2)
        if (crcReceived != crcCalc) return null

        val regCount = byteCount / 2
        val registers = IntArray(regCount)
        for (i in 0 until regCount) {
            val hi = frame[3 + i * 2].toInt() and 0xFF
            val lo = frame[3 + i * 2 + 1].toInt() and 0xFF
            registers[i] = (hi shl 8) or lo
        }
        return ReadResponse(slaveId, funcRaw, registers)
    }

    fun registersToFloatLE(regLow: Int, regHigh: Int): Float {
        val bits = (regHigh shl 16) or (regLow and 0xFFFF)
        return Float.fromBits(bits)
    }

    fun registersToFloat(hi: Int, lo: Int): Float {
        val bits = (hi shl 16) or (lo and 0xFFFF)
        return Float.fromBits(bits)
    }

    fun registersToInt32(hi: Int, lo: Int): Int {
        return (hi shl 16) or (lo and 0xFFFF)
    }

    fun floatToRegistersLE(value: Float): IntArray {
        val bits = value.toRawBits()
        val low = bits and 0xFFFF
        val high = (bits ushr 16) and 0xFFFF
        return intArrayOf(low, high)
    }

    fun intToRegistersLE(value: Int): IntArray {
        val low = value and 0xFFFF
        val high = (value ushr 16) and 0xFFFF
        return intArrayOf(low, high)
    }

    fun registersToIntLE(regLow: Int, regHigh: Int): Int {
        return (regHigh shl 16) or (regLow and 0xFFFF)
    }

    fun uint32ToRegistersLEByteSwap(value: Int): IntArray {
        val b0 = value and 0xFF
        val b1 = (value ushr 8) and 0xFF
        val b2 = (value ushr 16) and 0xFF
        val b3 = (value ushr 24) and 0xFF
        val register0 = (b0 shl 8) or b1
        val register1 = (b2 shl 8) or b3
        return intArrayOf(register0, register1)
    }

    fun registersToUint32LEByteSwap(register0: Int, register1: Int): Int {
        val b0 = (register0 ushr 8) and 0xFF
        val b1 = register0 and 0xFF
        val b2 = (register1 ushr 8) and 0xFF
        val b3 = register1 and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}