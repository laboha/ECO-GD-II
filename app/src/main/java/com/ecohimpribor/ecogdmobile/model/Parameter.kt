package com.ecohimpribor.ecogdmobile.model

data class Parameter(
    val name: String,
    var value: String,
    val unit: String,
    var isChanged: Boolean = false,
    val editable: Boolean = false
)

data class EmulationFunction(
    val name: String,
    var value: String = "",
    var isEnabled: Boolean = false,
    val hasValueField: Boolean = true
)