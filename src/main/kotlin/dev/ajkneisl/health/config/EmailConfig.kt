package dev.ajkneisl.health.config

import kotlinx.serialization.Serializable

@Serializable
data class EmailConfig(
    val enabled: Boolean = false,
    val region: String = "us-east-1",
    val from: String = "",
    val to: List<String> = emptyList(),
    val subjectPrefix: String = "[health]",
    val cooldownMinutes: Long = 15,
    /** Down or degraded checks in a row before an endpoint counts towards an alert. */
    val alertAfterChecks: Int = 5,
    /**
     * Degraded endpoints are mailed about together, and only while more than this many of them are
     * degraded at once.
     */
    val degradedAlertAbove: Int = 2,
    /** The shortest gap between two degraded emails, however often endpoints flap. */
    val degradedCooldownMinutes: Long = 10,
    val notifyOnRecovery: Boolean = false,
    val dailyReport: DailyReportConfig = DailyReportConfig(),
) {
    val deliverable: Boolean
        get() = enabled && from.isNotBlank() && to.isNotEmpty()
}
