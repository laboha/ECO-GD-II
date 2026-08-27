package com.ecohimpribor.ecogdmobile.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView

/**
 * Управляет светодиодом-индикатором на фото прибора: точка (ivStatusLed) +
 * мягкое радиальное свечение позади неё (ivLedGlow). Без мигания — каждый
 * режим горит ровным светом своего цвета; OFF гасит и точку, и свечение.
 */
class StatusLedController(
    private val dot: ImageView,
    private val glow: ImageView
) {
    private var currentMode: LedMode? = null

    fun setMode(mode: LedMode) {
        if (currentMode == mode) return
        currentMode = mode

        if (mode == LedMode.OFF) {
            dot.backgroundTintList = ColorStateList.valueOf(mode.color)
            glow.visibility = View.INVISIBLE
            return
        }

        dot.backgroundTintList = ColorStateList.valueOf(mode.color)
        glow.backgroundTintList = ColorStateList.valueOf(mode.color)
        glow.visibility = View.VISIBLE
    }
}