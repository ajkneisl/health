package dev.ajkneisl.health.monitor

import kotlinx.serialization.Serializable

@Serializable
data class CheckResult(
    val status: Status,
    val checkedAt: Long,
    val latencyMillis: Long,
    val httpStatus: Int? = null,
    val error: String? = null,
    /** Optional top-level `version` string reported by the endpoint. */
    val version: String? = null,
    val stats: List<Stat> = emptyList(),
)
