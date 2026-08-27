package com.ecohimpribor.ecogdmobile.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.ecohimpribor.ecogdmobile.R
import com.ecohimpribor.ecogdmobile.ble.LatestReading

class ParametersFragment : Fragment(R.layout.fragment_parameters) {

    private val baudRates = listOf(9600, 19200, 38400, 57600, 115200)

    private lateinit var tvParamsEmpty: TextView
    private lateinit var tvModbusIdValue: TextView
    private lateinit var tvBaudRateValue: TextView
    private lateinit var tvThreshold1Value: TextView
    private lateinit var tvThreshold2Value: TextView
    private lateinit var tvStatusValue: TextView
    private var modbusIdHost: ModbusIdHost? = null
    private var baudRateHost: BaudRateHost? = null
    private var thresholdsHost: ThresholdsHost? = null
    private var writeHost: ParametersWriteHost? = null

    private val readingListener = { refresh() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        modbusIdHost = activity as? ModbusIdHost
        baudRateHost = activity as? BaudRateHost
        thresholdsHost = activity as? ThresholdsHost
        writeHost = activity as? ParametersWriteHost

        tvParamsEmpty = view.findViewById(R.id.tvParamsEmpty)
        tvModbusIdValue = view.findViewById(R.id.tvModbusIdValue)
        tvBaudRateValue = view.findViewById(R.id.tvBaudRateValue)
        tvThreshold1Value = view.findViewById(R.id.tvThreshold1Value)
        tvThreshold2Value = view.findViewById(R.id.tvThreshold2Value)
        tvStatusValue = view.findViewById(R.id.tvStatusValue)

        view.findViewById<View>(R.id.rowModbusId).isClickable = false

        view.findViewById<View>(R.id.rowBaudRate).setOnClickListener {
            showBaudRateDialog()
        }

        view.findViewById<View>(R.id.rowThreshold1).setOnClickListener {
            showEditDialog("Порог 1", formatThreshold(currentThreshold1()), decimal = true) { newValue ->
                val newT1 = newValue.toFloatOrNull()
                if (newT1 == null) {
                    showResultDialog(false, "Введите число, например 25.02.")
                    return@showEditDialog
                }
                sendThresholds(threshold1 = newT1)
            }
        }

        view.findViewById<View>(R.id.rowThreshold2).setOnClickListener {
            showEditDialog("Порог 2", formatThreshold(currentThreshold2()), decimal = true) { newValue ->
                val newT2 = newValue.toFloatOrNull()
                if (newT2 == null) {
                    showResultDialog(false, "Введите число, например 51.00.")
                    return@showEditDialog
                }
                sendThresholds(threshold2 = newT2)
            }
        }

        view.findViewById<MaterialButton>(R.id.btnRefreshParams).setOnClickListener {
            writeHost?.requestParametersRead()
        }

        refresh()
    }

    override fun onStart() {
        super.onStart()
        LatestReading.addListener(readingListener)
        refresh()
        if (LatestReading.isConnected) {
            writeHost?.requestParametersRead()
        }
    }

    override fun onStop() {
        LatestReading.removeListener(readingListener)
        super.onStop()
    }

    private fun currentBaudRate(): Int = LatestReading.baudRate ?: baudRateHost?.getBaudRate() ?: 115200
    private fun currentThreshold1(): Float = LatestReading.threshold1 ?: thresholdsHost?.getThresholds()?.first ?: 25.02f
    private fun currentThreshold2(): Float = LatestReading.threshold2 ?: thresholdsHost?.getThresholds()?.second ?: 51.00f

    private fun formatThreshold(value: Float): String = String.format("%.2f", value)

    private fun refresh() {
        val connected = LatestReading.isConnected
        rowsContainerVisible(connected)
        if (!connected) return

        val modbusId = LatestReading.modbusId ?: modbusIdHost?.getModbusId()
        tvModbusIdValue.text = modbusId?.toString() ?: "—"
        tvBaudRateValue.text = currentBaudRate().toString()
        tvThreshold1Value.text = formatThreshold(currentThreshold1())
        tvThreshold2Value.text = formatThreshold(currentThreshold2())
        tvStatusValue.text = LatestReading.statusLabel.ifBlank { "—" }
    }

    private fun rowsContainerVisible(visible: Boolean) {
        val view = view ?: return
        val ids = listOf(R.id.rowModbusId, R.id.rowBaudRate, R.id.rowThreshold1, R.id.rowThreshold2, R.id.rowStatus, R.id.btnRefreshParams)
        for (id in ids) {
            view.findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
        }
        tvParamsEmpty.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun sendBaudRate(newRate: Int) {
        val host = writeHost ?: run { showResultDialog(false, "Нет подключения."); return }
        host.writeBaudRateToDevice(newRate) { success, reason ->
            if (success) {
                showResultDialog(true, "Данные успешно изменены")
                refresh()
            } else {
                showResultDialog(false, appendPasswordHint(reason))
            }
        }
    }

    private fun sendThresholds(
        threshold1: Float = currentThreshold1(),
        threshold2: Float = currentThreshold2()
    ) {
        val host = writeHost ?: run { showResultDialog(false, "Нет подключения."); return }
        host.writeThresholdsToDevice(threshold1, threshold2) { success, reason ->
            if (success) {
                showResultDialog(true, "Данные успешно изменены")
                refresh()
            } else {
                showResultDialog(false, appendPasswordHint(reason))
            }
        }
    }

    private fun appendPasswordHint(reason: String): String {
        if (reason.contains("Не принято") || reason.contains("Нет ответа")) {
            return if (LatestReading.isWritePasswordConfirmed) {
                "$reason\n\nНе принято."
            } else {
                "$reason\n\nНеверный пароль."
            }
        }
        return reason
    }

    private fun showEditDialog(title: String, currentValue: String, decimal: Boolean, onSave: (String) -> Unit) {
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val input = EditText(context).apply {
            inputType = if (decimal) {
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                android.text.InputType.TYPE_CLASS_NUMBER
            }
            setText(currentValue)
            setSelection(text.length)
        }
        container.addView(input)

        AlertDialog.Builder(context)
            .setTitle("Сменить значение «$title» на:")
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ -> onSave(input.text.toString().trim()) }
            .create()
            .show()
    }

    private fun showBaudRateDialog() {
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val spinner = Spinner(context)
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, baudRates.map { it.toString() })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val currentIndex = baudRates.indexOf(currentBaudRate()).let { if (it >= 0) it else baudRates.size - 1 }
        spinner.setSelection(currentIndex)
        container.addView(spinner)

        AlertDialog.Builder(context)
            .setTitle("Сменить значение «Скорость» на:")
            .setNegativeButton("Отмена", null)
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ -> sendBaudRate(baudRates[spinner.selectedItemPosition]) }
            .create()
            .show()
    }

    private fun showResultDialog(success: Boolean, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(if (success) "Готово" else "Ошибка")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}