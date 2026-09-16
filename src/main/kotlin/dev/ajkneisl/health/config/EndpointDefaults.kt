package dev.ajkneisl.health.config

import kotlinx.serialization.Serializable

@Serializable
data class EndpointDefaults(
    val type: CheckType = CheckType.FULL,
    /** Seconds between checks of the same endpoint. */
    val intervalSeconds: Long = 30,
    /** Hard cap on a single check, including reading the body. */
    val timeoutMillis: Long = 5_000,
    /** Number of past checks kept per endpoint for the sparkline and window uptime. */
    val historySize: Int = 40,
    /** A response slower than this is reported as DEGRADED rather than UP. */
    val degradedAfterMillis: Long = 1_000,
    val expectedStatuses: List<Int> = listOf(200),
    /**
     * Where the optional statistics object lives in the JSON response. Dotted paths are
     * supported (`"data.stats"`); an empty string treats the whole body as the stats object.
     */
    val statsPath: String = "stats",
)
