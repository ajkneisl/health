package dev.ajkneisl.health.config

/** An [EndpointConfig] with every default resolved, plus a stable id used in URLs and markup. */
data class Endpoint(
    val id: String,
    val name: String,
    val url: String,
    val type: CheckType,
    val method: String,
    val group: String,
    val headers: Map<String, String>,
    val intervalSeconds: Long,
    val timeoutMillis: Long,
    val historySize: Int,
    val degradedAfterMillis: Long,
    val expectedStatuses: List<Int>,
    val statsPath: String,
)

fun HealthConfig.resolveEndpoints(): List<Endpoint> {
    val used = mutableSetOf<String>()
    return endpoints.map { endpoint ->
        val base = endpoint.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "endpoint" }
        var id = base
        var suffix = 2
        while (!used.add(id)) id = "$base-${suffix++}"

        Endpoint(
            id = id,
            name = endpoint.name,
            url = endpoint.url,
            type = endpoint.type ?: defaults.type,
            method = endpoint.method.uppercase(),
            group = endpoint.group ?: "Endpoints",
            headers = endpoint.headers,
            intervalSeconds = (endpoint.intervalSeconds
                ?: defaults.intervalSeconds).coerceAtLeast(1),
            timeoutMillis = (endpoint.timeoutMillis ?: defaults.timeoutMillis).coerceAtLeast(100),
            historySize = (endpoint.historySize ?: defaults.historySize).coerceIn(1, 500),
            degradedAfterMillis = endpoint.degradedAfterMillis ?: defaults.degradedAfterMillis,
            expectedStatuses = endpoint.expectedStatuses ?: defaults.expectedStatuses,
            statsPath = endpoint.statsPath ?: defaults.statsPath,
        )
    }
}
