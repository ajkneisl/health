package dev.ajkneisl.health.config

import kotlinx.serialization.Serializable

/**
 * Dashboard-wide configuration, normally read from `health.json`.
 *
 * Every endpoint may override any of the [defaults]; anything it leaves out is inherited.
 */
@Serializable
data class HealthConfig(
    /** How often the dashboard page refreshes itself, in seconds. */
    val refreshSeconds: Int = 15,
    val title: String = "Health",
    val defaults: EndpointDefaults = EndpointDefaults(),
    val endpoints: List<EndpointConfig> = emptyList(),
    val email: EmailConfig = EmailConfig(),
    /**
     * Checked on the default interval but not shown on the dashboard. Alerts are only mailed while
     * its last check was a 200, so losing our own connection does not mail about every endpoint.
     * Blank disables the baseline.
     */
    val baselineUrl: String = "https://www.google.com",
)
