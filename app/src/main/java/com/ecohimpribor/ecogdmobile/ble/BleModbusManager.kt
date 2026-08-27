package com.ecohimpribor.ecogdmobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream

class BleModbusManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onDeviceFound(device: BluetoothDevice, rssi: Int)
        fun onScanFinished()
        fun onConnectionStateChanged(connected: Boolean)
        fun onRegistersReceived(startAddress: Int, registers: IntArray)
        fun onModbusError(errorCode: Int)
        fun onError(message: String)
        fun onCommunicationTimeout()
    }

    companion object {
        private const val TAG = "BleModbus"
        const val WRITE_USES_SEPARATE_TX_CHARACTERISTIC = false
        private const val MISSED_RESPONSE_THRESHOLD = 3
    }

    private fun log(msg: String) = Log.d(TAG, msg)
    private fun logWarn(msg: String) = Log.w(TAG, msg)
    private fun logError(msg: String) = Log.e(TAG, msg)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isScanning = false
    private var isConnected = false
    private var slaveId: Int = 1
    private var didRetryDiscovery = false

    private val incomingBuffer = ByteArrayOutputStream()
    private var lastRequestedStartAddress: Int = -1

    private var pendingWriteCallback: ((Boolean) -> Unit)? = null
    private var pendingVerification: ((IntArray) -> Unit)? = null
    private var verificationTimeoutRunnable: Runnable? = null

    private var pollingRunnable: Runnable? = null
    private var pollingStartAddress: Int = ModbusRtu.LIVE_BLOCK_START
    private var pollingCount: Int = ModbusRtu.LIVE_BLOCK_COUNT
    private var pollingIntervalMs: Long = 1000L

    private var awaitingResponse = false
    private var missedResponseCount = 0
    private var timeoutAlreadyReported = false
    private var writePassword: Int = ModbusRtu.DEFAULT_WRITE_PASSWORD

    fun setWritePassword(password: Int) {
        writePassword = password
    }

    // Сканирование

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Bluetooth выключен")
            return
        }
        if (scanner == null) {
            listener.onError("BLE сканер недоступен")
            return
        }
        if (isScanning) {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
        isScanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            log("startScan()")
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            isScanning = false
            listener.onError("Нет разрешения на сканирование Bluetooth")
            return
        } catch (e: Exception) {
            isScanning = false
            listener.onError("Не удалось запустить сканирование: ${e.message}")
            return
        }
        mainHandler.postDelayed({ stopScan() }, 12_000L)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        log("stopScan()")
        listener.onScanFinished()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            listener.onDeviceFound(result.device, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            logError("onScanFailed: $errorCode")
            listener.onError("Ошибка сканирования: $errorCode")
        }
    }

    // Подключение

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        didRetryDiscovery = false
        resetTimeoutTracking()
        log("connect() -> ${device.address}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopPolling()
        resetTimeoutTracking()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        notifyCharacteristic = null
        writeCharacteristic = null
        if (isConnected) {
            isConnected = false
            listener.onConnectionStateChanged(false)
        }
    }

    private fun resetTimeoutTracking() {
        awaitingResponse = false
        missedResponseCount = 0
        timeoutAlreadyReported = false
        pendingVerification = null
        verificationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        verificationTimeoutRunnable = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("onConnectionStateChange: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    mainHandler.post { listener.onConnectionStateChanged(false) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("onServicesDiscovered: status=$status, services=${g.services.map { it.uuid }}")

            val service = g.getService(BleUartConstants.SERVICE_UUID)
            if (service == null) {
                if (!didRetryDiscovery) {
                    didRetryDiscovery = true
                    logWarn("Сервис не найден с первой попытки, повтор через 300мс")
                    mainHandler.postDelayed({ g.discoverServices() }, 300L)
                    return
                }
                mainHandler.post { listener.onError("Сервис UART не найден на устройстве") }
                return
            }
            didRetryDiscovery = false

            val mainCharacteristic = service.getCharacteristic(BleUartConstants.CHARACTERISTIC_UUID)
            if (mainCharacteristic == null) {
                mainHandler.post { listener.onError("Характеристика UART не найдена") }
                return
            }

            notifyCharacteristic = mainCharacteristic
            writeCharacteristic = if (WRITE_USES_SEPARATE_TX_CHARACTERISTIC) {
                service.getCharacteristic(BleUartConstants.CHARACTERISTIC_TX_UUID) ?: mainCharacteristic
            } else {
                mainCharacteristic
            }
            log("Характеристики: notify=${notifyCharacteristic?.uuid} write=${writeCharacteristic?.uuid}")

            g.setCharacteristicNotification(mainCharacteristic, true)
            val cccd = mainCharacteristic.getDescriptor(BleUartConstants.CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } else {
                logWarn("Дескриптор CCCD (2902) не найден")
            }

            isConnected = true
            resetTimeoutTracking()
            mainHandler.post { listener.onConnectionStateChanged(true) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            log("onCharacteristicChanged (${characteristic.uuid}): ${value.toHexString()}")
            handleIncomingBytes(value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("onDescriptorWrite: status=$status")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("onCharacteristicWrite: status=$status")
            pendingWriteCallback?.let { callback ->
                pendingWriteCallback = null
                val ok = status == BluetoothGatt.GATT_SUCCESS
                mainHandler.post { callback(ok) }
            }
        }
    }

    private fun handleIncomingBytes(chunk: ByteArray) {
        incomingBuffer.write(chunk)
        val buffered = incomingBuffer.toByteArray()

        val response = ModbusRtu.parseReadResponse(buffered) ?: run {
            if (buffered.size > 260) incomingBuffer.reset()
            return
        }

        incomingBuffer.reset()
        awaitingResponse = false
        missedResponseCount = 0
        timeoutAlreadyReported = false

        if (response.isError) {
            logWarn("Modbus-ошибка: код ${response.errorCode}")
            mainHandler.post { listener.onModbusError(response.errorCode) }
            return
        }

        log("Разобран ответ: ${response.registers.toList()}")
        val startAddr = if (lastRequestedStartAddress >= 0) lastRequestedStartAddress else 0

        pendingVerification?.let { callback ->
            pendingVerification = null
            verificationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            verificationTimeoutRunnable = null
            mainHandler.post { callback(response.registers) }
        }

        mainHandler.post { listener.onRegistersReceived(startAddr, response.registers) }
    }

    // Запросы Modbus

    @SuppressLint("MissingPermission")
    fun readRegisters(startAddress: Int, count: Int) {
        val characteristic = writeCharacteristic ?: return
        val g = gatt ?: return

        if (awaitingResponse) {
            missedResponseCount++
            if (missedResponseCount >= MISSED_RESPONSE_THRESHOLD && !timeoutAlreadyReported) {
                timeoutAlreadyReported = true
                mainHandler.post { listener.onCommunicationTimeout() }
            }
        }
        awaitingResponse = true

        incomingBuffer.reset()
        lastRequestedStartAddress = startAddress
        val packet = ModbusRtu.buildReadPacket(slaveId, startAddress, count)
        log("readRegisters(start=$startAddress, count=$count)")
        writeToCharacteristic(g, characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    @SuppressLint("MissingPermission")
    fun writeSingleRegister(address: Int, value: Int) {
        val characteristic = writeCharacteristic ?: return
        val g = gatt ?: return
        val packet = ModbusRtu.buildWriteSingleRegisterPacket(slaveId, address, value)
        writeToCharacteristic(g, characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    fun readParametersBlock() {
        readRegisters(ModbusRtu.PARAMS_ID_BLOCK_START, ModbusRtu.PARAMS_ID_BLOCK_COUNT)
        mainHandler.postDelayed({
            if (isConnected) {
                readRegisters(ModbusRtu.PARAMS_THRESHOLD_BLOCK_START, ModbusRtu.PARAMS_THRESHOLD_BLOCK_COUNT)
            }
        }, 350L)
        mainHandler.postDelayed({
            if (isConnected) {
                readRegisters(ModbusRtu.REG_STORED_PASSWORD, 2)
            }
        }, 700L)
    }

    fun readStoredPassword() {
        readRegisters(ModbusRtu.REG_STORED_PASSWORD, 2)
    }

    fun writeBaudRate(baudRateCode: Int, onResult: (Boolean, String) -> Unit) {
        val regs = ModbusRtu.intToRegistersLE(baudRateCode)
        writeAndVerify(
            writeStartAddress = ModbusRtu.REG_TR_BAUDRATE,
            values = regs,
            verifyStartAddress = ModbusRtu.REG_TR_BAUDRATE,
            verifyCount = 2,
            matches = { readRegs -> ModbusRtu.registersToIntLE(readRegs[0], readRegs[1]) == baudRateCode },
            onResult = onResult
        )
    }

    fun writeThresholds(threshold1: Float, threshold2: Float, onResult: (Boolean, String) -> Unit) {
        val t1 = ModbusRtu.floatToRegistersLE(threshold1)
        val t2 = ModbusRtu.floatToRegistersLE(threshold2)
        writeAndVerify(
            writeStartAddress = ModbusRtu.PARAMS_THRESHOLD_BLOCK_START,
            values = intArrayOf(t1[0], t1[1], t2[0], t2[1]),
            verifyStartAddress = ModbusRtu.PARAMS_THRESHOLD_BLOCK_START,
            verifyCount = ModbusRtu.PARAMS_THRESHOLD_BLOCK_COUNT,
            matches = { regs ->
                val readT1 = ModbusRtu.registersToFloatLE(regs[0], regs[1])
                val readT2 = ModbusRtu.registersToFloatLE(regs[2], regs[3])
                kotlin.math.abs(readT1 - threshold1) < 0.01f && kotlin.math.abs(readT2 - threshold2) < 0.01f
            },
            onResult = onResult
        )
    }

    private fun writeAndVerify(
        writeStartAddress: Int,
        values: IntArray,
        verifyStartAddress: Int,
        verifyCount: Int,
        matches: (IntArray) -> Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        val characteristic = writeCharacteristic
        val g = gatt
        if (characteristic == null || g == null || !isConnected) {
            onResult(false, "Нет подключения")
            return
        }

        val wasPolling = pollingRunnable != null
        stopPolling()

        fun finish(success: Boolean, reason: String) {
            if (wasPolling) startPolling(pollingStartAddress, pollingCount, pollingIntervalMs)
            onResult(success, reason)
        }

        fun sendBlock(startAddress: Int, blockValues: IntArray, onAck: (Boolean) -> Unit) {
            val packet = ModbusRtu.buildWriteMultipleRegistersPacket(slaveId, startAddress, blockValues)
            log("write10(start=$startAddress, values=${blockValues.toList()})")
            pendingWriteCallback = { writeOk -> onAck(writeOk) }
            writeToCharacteristic(g, characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }

        val passwordRegs = ModbusRtu.uint32ToRegistersLEByteSwap(writePassword)

        sendBlock(ModbusRtu.REG_WRITE_UNLOCK, passwordRegs) { passwordOk ->
            if (!passwordOk) {
                logWarn("Запись пароля не подтверждена по BLE")
                finish(false, "Отклонено BLE")
            } else {
                mainHandler.postDelayed({
                    sendBlock(writeStartAddress, values) { valuesOk ->
                        if (!valuesOk) {
                            logWarn("Запись значения не подтверждена по BLE")
                            finish(false, "Отклонено BLE")
                        } else {
                            mainHandler.postDelayed({
                                verifyByReadback(verifyStartAddress, verifyCount, matches) { ok, reason -> finish(ok, reason) }
                            }, 400L)
                        }
                    }
                }, 150L)
            }
        }
    }

    private fun verifyByReadback(
        verifyStartAddress: Int,
        verifyCount: Int,
        matches: (IntArray) -> Boolean,
        onDone: (Boolean, String) -> Unit
    ) {
        pendingVerification = { registers ->
            val ok = registers.size >= verifyCount && matches(registers)
            log("Верификация (адрес $verifyStartAddress): совпало=$ok, регистры=${registers.toList()}")
            onDone(ok, if (ok) "" else "Не принято")
        }

        val timeoutRunnable = Runnable {
            if (pendingVerification != null) {
                logWarn("Верификация: таймаут (адрес $verifyStartAddress)")
                pendingVerification = null
                onDone(false, "Нет ответа")
            }
        }
        verificationTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, 4000L)

        readRegisters(verifyStartAddress, verifyCount)
    }

    @SuppressLint("MissingPermission")
    private fun writeToCharacteristic(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int
    ) {
        characteristic.writeType = writeType
        characteristic.value = data
        g.writeCharacteristic(characteristic)
    }

    fun setSlaveId(id: Int) {
        slaveId = id
    }

    // Периодический опрос показаний

    fun startPolling(startAddress: Int = ModbusRtu.LIVE_BLOCK_START, count: Int = ModbusRtu.LIVE_BLOCK_COUNT, intervalMs: Long = 1000L) {
        pollingStartAddress = startAddress
        pollingCount = count
        pollingIntervalMs = intervalMs
        stopPolling()
        val runnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    readRegisters(pollingStartAddress, pollingCount)
                }
                mainHandler.postDelayed(this, pollingIntervalMs)
            }
        }
        pollingRunnable = runnable
        mainHandler.post(runnable)
    }

    fun stopPolling() {
        pollingRunnable?.let { mainHandler.removeCallbacks(it) }
        pollingRunnable = null
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(" ") { String.format("%02X", it) }