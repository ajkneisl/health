package dev.ajkneisl.health.config

import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class DailyReportConfig(
    val enabled: Boolean = true,
    /** Local time of day, `HH:mm`. */
    val at: String = "08:00",
    /** IANA zone id; empty means the server's zone. */
    val zone: String = "",
) {
    fun localTime(): LocalTime = runCatching {
        LocalTime.parse(at)
    }.getOrElse { LocalTime.of(8, 0) }

    fun zoneId(): ZoneId =
        zone.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
}
