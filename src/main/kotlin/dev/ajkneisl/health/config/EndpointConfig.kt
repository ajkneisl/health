package dev.ajkneisl.health.config

import kotlinx.serialization.Serializable

@Serializable
data class EndpointConfig(
    val name: String,
    val url: String,
    val type: CheckType? = null,
    val method: String = "GET",
    val group: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val intervalSeconds: Long? = null,
    val timeoutMillis: Long? = null,
    val historySize: Int? = null,
    val degradedAfterMillis: Long? = null,
    val expectedStatuses: List<Int>? = null,
    val statsPath: String? = null,
)
