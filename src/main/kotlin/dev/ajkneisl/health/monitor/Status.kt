package dev.ajkneisl.health.monitor

enum class Status {
    UP,
    DEGRADED,
    DOWN,
    UNKNOWN;

    val label: String
        get() =
            when (this) {
                UP -> "Operational"
                DEGRADED -> "Degraded"
                DOWN -> "Down"
                UNKNOWN -> "Pending"
            }

    val slug: String
        get() = name.lowercase()
}
