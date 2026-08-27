package com.ecohimpribor.ecogdmobile.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ecohimpribor.ecogdmobile.R
import com.ecohimpribor.ecogdmobile.model.Parameter

class ParameterAdapter(
    private val items: MutableList<Parameter>,
    private val editable: Boolean,
    private val onValueChanged: ((Parameter) -> Unit)? = null
) : RecyclerView.Adapter<ParameterAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvParamName)
        val etValue: EditText = view.findViewById(R.id.etParamValue)
        val tvUnit: TextView = view.findViewById(R.id.tvParamUnit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_parameter, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val rowEditable = editable || item.editable

        holder.tvName.text = item.name
        holder.tvUnit.text = item.unit
        holder.etValue.isEnabled = rowEditable
        holder.etValue.isFocusable = rowEditable
        holder.etValue.isFocusableInTouchMode = rowEditable

        holder.etValue.tag?.let { (it as? TextWatcher)?.let { tw -> holder.etValue.removeTextChangedListener(tw) } }

        if (holder.etValue.text.toString() != item.value) {
            holder.etValue.setText(item.value)
        }

        applyChangedColor(holder, item.isChanged)

        if (rowEditable) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val newValue = s?.toString().orEmpty()
                    if (newValue != item.value) {
                        item.isChanged = true
                        item.value = newValue
                        applyChangedColor(holder, true)
                        onValueChanged?.invoke(item)
                    }
                }
            }
            holder.etValue.addTextChangedListener(watcher)
            holder.etValue.tag = watcher
        }
    }

    private fun applyChangedColor(holder: ViewHolder, changed: Boolean) {
        val colorRes = if (changed) R.color.value_changed else R.color.text_primary
        holder.etValue.setTextColor(ContextCompat.getColor(holder.itemView.context, colorRes))
    }

    override fun getItemCount(): Int = items.size
}