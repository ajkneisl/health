package dev.ajkneisl.health.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How much of a response a check looks at. */
@Serializable
enum class CheckType {
    /** Reads the body for `status`, `version` and `stats`, and flags slow responses as degraded. */
    @SerialName("full") FULL,

    /** Up when the response is a 200, down otherwise. The body is never read. */
    @SerialName("simple") SIMPLE,
}
