package dev.ajkneisl.health.monitor

import kotlinx.serialization.Serializable

@Serializable
data class Stat(val label: String, val value: String, val numeric: Double? = null)
