package dev.ajkneisl.health.config

import java.io.File
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

object ConfigLoader {
    private val log = LoggerFactory.getLogger(ConfigLoader::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * Loads the configuration from, in order: [path], the `HEALTH_CONFIG` environment variable,
     * `health.json` in the working directory, or `health.json` on the classpath. Falls back to an
     * empty configuration when none of those exist.
     */
    fun load(path: String? = null): HealthConfig {
        val candidates = listOfNotNull(path, System.getenv("HEALTH_CONFIG"), "health.json")
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.isFile) {
                log.info("Loading health configuration from {}", file.absolutePath)
                return parse(file.readText())
            }
        }

        val classpath = ConfigLoader::class.java.getResourceAsStream("/health.json")
        if (classpath != null) {
            log.info("Loading health configuration from classpath:/health.json")
            return classpath.bufferedReader().use { parse(it.readText()) }
        }

        log.warn(
            "No health configuration found (looked for {}); starting with no endpoints",
            candidates,
        )
        return HealthConfig()
    }

    fun parse(text: String): HealthConfig = json.decodeFromString(HealthConfig.serializer(), text)
}
