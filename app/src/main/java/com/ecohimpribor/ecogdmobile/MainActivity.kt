package com.ecohimpribor.ecogdmobile

import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.ecohimpribor.ecogdmobile.ble.BleModbusManager
import com.ecohimpribor.ecogdmobile.ble.FaultCodes
import com.ecohimpribor.ecogdmobile.ble.GasCatalog
import com.ecohimpribor.ecogdmobile.ble.LatestReading
import com.ecohimpribor.ecogdmobile.ble.ModbusRtu
import com.ecohimpribor.ecogdmobile.ui.BaudRateHost
import com.ecohimpribor.ecogdmobile.ui.DeviceStatus
import com.ecohimpribor.ecogdmobile.ui.LedMode
import com.ecohimpribor.ecogdmobile.ui.LedTestHost
import com.ecohimpribor.ecogdmobile.ui.MainPagerAdapter
import com.ecohimpribor.ecogdmobile.ui.ModbusIdHost
import com.ecohimpribor.ecogdmobile.ui.ParametersWriteHost
import com.ecohimpribor.ecogdmobile.ui.StatusLedController
import com.ecohimpribor.ecogdmobile.ui.ThresholdsHost
import com.ecohimpribor.ecogdmobile.ui.WritePasswordHost

class MainActivity :
    AppCompatActivity(),
    BleModbusManager.Listener,
    LedTestHost,
    ThresholdsHost,
    ModbusIdHost,
    BaudRateHost,
    WritePasswordHost,
    ParametersWriteHost {

    private lateinit var bleManager: BleModbusManager

    private val foundDevices = LinkedHashMap<String, BluetoothDevice>()
    private lateinit var foundDevicesAdapter: ArrayAdapter<String>

    private lateinit var btnSearch: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var spinnerFoundDevices: Spinner
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var cbAllDevices: CheckBox
    private lateinit var tvModbusIdLabel: TextView
    private lateinit var etModbusIdMain: EditText
    private lateinit var ledController: StatusLedController

    private var isConnectedToDevice = false
    private var connectedDeviceLabel: String = "Устройство подключено"

    private var threshold1 = 25.02f
    private var threshold2 = 51.00f
    private var modbusId = 7
    private var baudRate = 115200
    private var writePassword = ModbusRtu.DEFAULT_WRITE_PASSWORD

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        if (grantResults.values.all { it }) {
            doStartScan()
        } else {
            Toast.makeText(this, "Нужны разрешения Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bleManager = BleModbusManager(this, this)

        ledController = StatusLedController(
            dot = findViewById(R.id.ivStatusLed),
            glow = findViewById(R.id.ivLedGlow)
        )
        ledController.setMode(LedMode.OFF)

        setupDeviceTypeSpinner()
        setupTabs()
        setupCollapsibleTabs()
        setupConnectionControls()
        resetOnScreenDefaults()
        applyBottomInsetPadding()
    }

    private fun applyBottomInsetPadding() {
        val root = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom)
            insets
        }
    }

    override fun onDestroy() {
        bleManager.disconnect()
        super.onDestroy()
    }

    private fun setupDeviceTypeSpinner() {
        val spinnerDeviceType = findViewById<Spinner>(R.id.spinnerDeviceType)
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.device_types,
            android.R.layout.simple_spinner_item
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerDeviceType.adapter = adapter
        spinnerDeviceType.setSelection(0)
    }

    private fun setupTabs() {
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 1
    }

    private var isTabsPanelExpanded = false

    private fun setupCollapsibleTabs() {
        val header = findViewById<View>(R.id.tabsToggleHeader)
        header.setOnClickListener {
            isTabsPanelExpanded = !isTabsPanelExpanded
            setTabsPanelExpanded(isTabsPanelExpanded)
        }
    }

    private fun setTabsPanelExpanded(expanded: Boolean) {
        val root = findViewById<ConstraintLayout>(R.id.main)
        val chevron = findViewById<ImageView>(R.id.ivTabsToggleChevron)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        TransitionManager.beginDelayedTransition(root)

        val constraintSet = ConstraintSet()
        constraintSet.clone(root)

        if (expanded) {
            constraintSet.clear(R.id.tabsToggleHeader, ConstraintSet.BOTTOM)
            constraintSet.connect(
                R.id.tabsToggleHeader, ConstraintSet.TOP,
                R.id.guideHalfScreen, ConstraintSet.TOP
            )
        } else {
            constraintSet.clear(R.id.tabsToggleHeader, ConstraintSet.TOP)
            constraintSet.connect(
                R.id.tabsToggleHeader, ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM
            )
        }
        constraintSet.applyTo(root)

        viewPager.visibility = if (expanded) View.VISIBLE else View.GONE

        chevron.animate().rotation(if (expanded) 180f else 0f).setDuration(150).start()
    }

    private fun showConnectionDependentPanel(connected: Boolean) {
        val header = findViewById<View>(R.id.tabsToggleHeader)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        if (connected) {
            isTabsPanelExpanded = false
            setTabsPanelExpanded(false)

            header.visibility = View.VISIBLE
            header.alpha = 0f
            header.translationY = 250f
            header.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(280)
                .start()
        } else {
            if (header.visibility != View.VISIBLE) return
            header.animate()
                .translationY(250f)
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    header.visibility = View.GONE
                    header.translationY = 0f
                    header.alpha = 1f
                    viewPager.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupConnectionControls() {
        btnSearch = findViewById(R.id.btnSearch)
        btnStop = findViewById(R.id.btnStop)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        spinnerFoundDevices = findViewById(R.id.spinnerFoundDevices)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        cbAllDevices = findViewById(R.id.cbAllDevices)
        tvModbusIdLabel = findViewById(R.id.tvModbusIdLabel)
        etModbusIdMain = findViewById(R.id.etModbusIdMain)

        foundDevicesAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
        foundDevicesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFoundDevices.adapter = foundDevicesAdapter

        spinnerFoundDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (foundDevicesAdapter.count == 0) return
                etModbusIdMain.setText(modbusId.toString())
                tvModbusIdLabel.visibility = View.VISIBLE
                etModbusIdMain.visibility = View.VISIBLE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSearch.setOnClickListener { requestPermissionsAndScan() }
        btnStop.setOnClickListener { bleManager.stopScan() }
        btnConnect.setOnClickListener { connectToSelectedDevice() }
        btnDisconnect.setOnClickListener { bleManager.disconnect() }
    }

    private fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestPermissionsAndScan() {
        val missing = requiredBlePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            doStartScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun doStartScan() {
        foundDevices.clear()
        foundDevicesAdapter.clear()
        foundDevicesAdapter.notifyDataSetChanged()

        tvModbusIdLabel.visibility = View.GONE
        etModbusIdMain.visibility = View.GONE

        tvConnectionStatus.text = "Поиск..."
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_wait))
        btnSearch.isEnabled = false
        btnStop.isEnabled = true

        bleManager.startScan()
    }

    private fun connectToSelectedDevice() {
        val position = spinnerFoundDevices.selectedItemPosition
        val address = foundDevices.keys.toList().getOrNull(position)
        val device = address?.let { foundDevices[it] }
        if (device == null) {
            Toast.makeText(this, "Найдите устройство", Toast.LENGTH_SHORT).show()
            return
        }

        etModbusIdMain.text.toString().toIntOrNull()?.let { modbusId = it }
        connectedDeviceLabel = foundDevicesAdapter.getItem(position) ?: "Устройство подключено"

        tvConnectionStatus.text = "Ожидайте"
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_wait))
        bleManager.setSlaveId(modbusId)
        bleManager.connect(device)
    }

    // BleModbusManager.Listener

    @android.annotation.SuppressLint("MissingPermission")
    override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
        val address = device.address ?: return
        if (foundDevices.containsKey(address)) return

        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        } ?: "Неизвестное устройство"

        val looksRelevant = name.contains("eco", ignoreCase = true) ||
                name.contains("device", ignoreCase = true)
        if (!cbAllDevices.isChecked && !looksRelevant) return

        foundDevices[address] = device
        foundDevicesAdapter.add("$name ($address)")
        foundDevicesAdapter.notifyDataSetChanged()
    }

    override fun onScanFinished() {
        btnSearch.isEnabled = true
        btnStop.isEnabled = false
        if (!isConnectedToDevice) {
            tvConnectionStatus.text = if (foundDevices.isEmpty()) "Таймаут" else "Ожидайте"
            tvConnectionStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (foundDevices.isEmpty()) R.color.status_timeout else R.color.status_wait
                )
            )
        }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        isConnectedToDevice = connected
        LatestReading.isConnected = connected

        btnConnect.isEnabled = !connected
        btnDisconnect.isEnabled = connected

        if (connected) {
            tvConnectionStatus.text = "Проверка Modbus..."
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_wait))
            tvDeviceName.text = connectedDeviceLabel

            bleManager.startPolling(ModbusRtu.LIVE_BLOCK_START, ModbusRtu.LIVE_BLOCK_COUNT, intervalMs = 1000L)

        } else {
            tvConnectionStatus.text = "Таймаут"
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_timeout))
            resetOnScreenDefaults()
            LatestReading.reset()
            showConnectionDependentPanel(false)
        }

        LatestReading.notifyChanged()
    }

    override fun onRegistersReceived(startAddress: Int, registers: IntArray) {
        when (startAddress) {
            ModbusRtu.LIVE_BLOCK_START -> handleLiveBlock(registers)
            ModbusRtu.PARAMS_ID_BLOCK_START -> handleIdBlock(registers)
            ModbusRtu.PARAMS_THRESHOLD_BLOCK_START -> handleThresholdBlock(registers)
            ModbusRtu.REG_STORED_PASSWORD -> handleStoredPasswordBlock(registers)
        }
    }

    private fun handleLiveBlock(registers: IntArray) {
        if (registers.size < ModbusRtu.LIVE_BLOCK_COUNT) return

        if (tvConnectionStatus.text != "ОК") {
            tvConnectionStatus.text = "ОК"
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
            bleManager.readParametersBlock()
            showConnectionDependentPanel(true)
        }

        val gasCode = registers[ModbusRtu.LIVE_OFFSET_GAS]
        val unitsCode = registers[ModbusRtu.LIVE_OFFSET_UNITS]
        val statusBitmask = registers[ModbusRtu.LIVE_OFFSET_STATUS]
        val range = ModbusRtu.registersToFloatLE(
            registers[ModbusRtu.LIVE_OFFSET_RANGE_LOW], registers[ModbusRtu.LIVE_OFFSET_RANGE_HIGH]
        )
        val concentration = ModbusRtu.registersToFloatLE(
            registers[ModbusRtu.LIVE_OFFSET_CONC_LOW], registers[ModbusRtu.LIVE_OFFSET_CONC_HIGH]
        )

        val fault = FaultCodes.lookup(statusBitmask)
        val status = DeviceStatus.from(statusBitmask, fault)

        val tvGasValue = findViewById<TextView>(R.id.tvGasValue)
        val tvGasUnits = findViewById<TextView>(R.id.tvGasUnits)
        val tvGasType = findViewById<TextView>(R.id.tvGasType)
        val ivGasStatusIcon = findViewById<ImageView>(R.id.ivGasStatusIcon)
        val tvGasStatus = findViewById<TextView>(R.id.tvGasStatus)

        tvGasType.text = GasCatalog.gasName(gasCode)
        ivGasStatusIcon.setImageResource(status.iconRes)
        tvGasStatus.text = status.label
        ledController.setMode(status.ledMode)

        if (status == DeviceStatus.FAULT) {
            tvGasValue.text = fault?.code ?: "E00"
            tvGasUnits.text = ""
            tvDeviceName.text = fault?.let { "${it.code} — ${it.description}" } ?: "Неисправность"
        } else {
            tvGasValue.text = String.format("%.1f", concentration)
            tvGasUnits.text = GasCatalog.unitName(unitsCode)
            tvDeviceName.text = connectedDeviceLabel
        }

        LatestReading.statusLabel = status.label
        LatestReading.concentration = concentration
        LatestReading.range = range
        LatestReading.gasCode = gasCode
        LatestReading.unitsCode = unitsCode
        LatestReading.faultCode = fault?.code
        LatestReading.notifyChanged()
    }

    private fun handleIdBlock(registers: IntArray) {
        if (registers.size < ModbusRtu.PARAMS_ID_BLOCK_COUNT) return

        modbusId = registers[ModbusRtu.PARAMS_ID_OFFSET_ADDRESS]
        baudRate = registers[ModbusRtu.PARAMS_ID_OFFSET_BAUDRATE] * 100

        LatestReading.modbusId = modbusId
        LatestReading.baudRate = baudRate
        LatestReading.notifyChanged()
    }

    private fun handleThresholdBlock(registers: IntArray) {
        if (registers.size < ModbusRtu.PARAMS_THRESHOLD_BLOCK_COUNT) return

        threshold1 = ModbusRtu.registersToFloatLE(
            registers[ModbusRtu.PARAMS_THRESHOLD_OFFSET_T1_LOW], registers[ModbusRtu.PARAMS_THRESHOLD_OFFSET_T1_HIGH]
        )
        threshold2 = ModbusRtu.registersToFloatLE(
            registers[ModbusRtu.PARAMS_THRESHOLD_OFFSET_T2_LOW], registers[ModbusRtu.PARAMS_THRESHOLD_OFFSET_T2_HIGH]
        )

        LatestReading.threshold1 = threshold1
        LatestReading.threshold2 = threshold2
        LatestReading.notifyChanged()
    }

    private fun handleStoredPasswordBlock(registers: IntArray) {
        if (registers.size < 2) return
        val realPassword = ModbusRtu.registersToUint32LEByteSwap(registers[0], registers[1])
        writePassword = realPassword
        bleManager.setWritePassword(realPassword)
        LatestReading.writePassword = realPassword
        LatestReading.isWritePasswordConfirmed = true
        LatestReading.notifyChanged()
        Toast.makeText(this, "Пароль записи прочитан с устройства: $realPassword", Toast.LENGTH_LONG).show()
    }

    override fun onModbusError(errorCode: Int) {
        android.util.Log.w("MainActivity", "Modbus exception: код $errorCode")
    }

    override fun onCommunicationTimeout() {
        tvConnectionStatus.text = "Ошибка Modbus"
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_timeout))
        showConnectionDependentPanel(false)
        resetOnScreenDefaults()
        LatestReading.reset()
        LatestReading.isConnected = isConnectedToDevice
        LatestReading.notifyChanged()
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        btnSearch.isEnabled = true
        btnStop.isEnabled = false
    }

    // LedTestHost

    override fun setLedTestMode(mode: LedMode) {
        ledController.setMode(mode)
    }

    // ThresholdsHost, ModbusIdHost, BaudRateHost

    override fun getThresholds(): Pair<Float, Float> = threshold1 to threshold2

    override fun setThresholds(threshold1: Float, threshold2: Float) {
        this.threshold1 = threshold1
        this.threshold2 = threshold2
    }

    override fun getModbusId(): Int = modbusId

    override fun setModbusId(id: Int) {
        modbusId = id
        if (::etModbusIdMain.isInitialized && etModbusIdMain.visibility == View.VISIBLE) {
            etModbusIdMain.setText(id.toString())
        }
    }

    override fun getBaudRate(): Int = baudRate

    override fun setBaudRate(rate: Int) {
        baudRate = rate
    }

    // WritePasswordHost

    override fun getWritePassword(): Int = writePassword

    override fun setWritePassword(password: Int) {
        writePassword = password
        bleManager.setWritePassword(password)
        LatestReading.isWritePasswordConfirmed = false
        LatestReading.writePassword = password
        LatestReading.notifyChanged()
    }

    // ParametersWriteHost

    override fun writeBaudRateToDevice(baudRate: Int, onResult: (Boolean, String) -> Unit) {
        if (!isConnectedToDevice) {
            onResult(false, "Нет подключения")
            return
        }
        bleManager.setWritePassword(writePassword)
        val baudRateCode = baudRate / 100
        bleManager.writeBaudRate(baudRateCode) { success, reason ->
            if (success) {
                this.baudRate = baudRate
                LatestReading.baudRate = baudRate
                LatestReading.notifyChanged()
            }
            onResult(success, reason)
        }
    }

    override fun writeThresholdsToDevice(threshold1: Float, threshold2: Float, onResult: (Boolean, String) -> Unit) {
        if (!isConnectedToDevice) {
            onResult(false, "Нет подключения")
            return
        }
        bleManager.setWritePassword(writePassword)
        bleManager.writeThresholds(threshold1, threshold2) { success, reason ->
            if (success) {
                this.threshold1 = threshold1
                this.threshold2 = threshold2
                LatestReading.threshold1 = threshold1
                LatestReading.threshold2 = threshold2
                LatestReading.notifyChanged()
            }
            onResult(success, reason)
        }
    }

    override fun requestParametersRead() {
        if (isConnectedToDevice) {
            bleManager.readParametersBlock()
        }
    }

    override fun requestStoredPasswordRead() {
        if (isConnectedToDevice) {
            bleManager.readStoredPassword()
        }
    }

    private fun resetOnScreenDefaults() {
        findViewById<TextView>(R.id.tvGasValue).text = "0.0"
        findViewById<TextView>(R.id.tvGasUnits).text = "% НКПР"
        findViewById<TextView>(R.id.tvGasType).text = "МЕТАН"
        findViewById<TextView>(R.id.tvGasStatus).text = "Норма"
        findViewById<ImageView>(R.id.ivGasStatusIcon).setImageResource(R.drawable.ic_status_check)
        ledController.setMode(LedMode.OFF)
        tvDeviceName.text = "Устройство не подключено"
    }
}