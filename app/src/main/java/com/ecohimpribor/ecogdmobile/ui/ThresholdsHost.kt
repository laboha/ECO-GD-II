package com.ecohimpribor.ecogdmobile.ui

interface ThresholdsHost {
    fun getThresholds(): Pair<Float, Float>
    fun setThresholds(threshold1: Float, threshold2: Float)
}