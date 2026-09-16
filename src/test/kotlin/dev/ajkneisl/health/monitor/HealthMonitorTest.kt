package dev.ajkneisl.health.monitor

import dev.ajkneisl.health.config.CheckType
import dev.ajkneisl.health.config.EndpointConfig
import dev.ajkneisl.health.config.EndpointDefaults
import dev.ajkneisl.health.config.HealthConfig
import dev.ajkneisl.health.config.resolveEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class HealthMonitorTest {

    @Test
    fun `check reads status, version and stats from the response`() {
        val body = """
            {
              "status": "ok",
              "version": "2.7.1",
              "stats": { "queueDepth": 12, "memory": { "usedMb": 412 }, "regions": ["us", "eu"] }
            }
        """.trimIndent()

        val result = runCheck(HttpStatusCode.OK, body)

        assertEquals(Status.UP, result.status)
        assertEquals("2.7.1", result.version)
        assertEquals(
            listOf("Queue depth" to "12", "Memory › Used mb" to "412", "Regions" to "us, eu"),
            result.stats.map { it.label to it.value },
        )
    }

    @Test
    fun `unexpected status code is down`() {
        val result = runCheck(HttpStatusCode.InternalServerError, "{}")

        assertEquals(Status.DOWN, result.status)
        assertEquals(500, result.httpStatus)
        assertNotNull(result.error)
    }

    @Test
    fun `endpoint reporting degraded is degraded even with a 200`() {
        val result = runCheck(HttpStatusCode.OK, """{"status":"degraded","version":"9"}""")

        assertEquals(Status.DEGRADED, result.status)
        assertEquals("9", result.version)
    }

    @Test
    fun `a simple check only looks at the status code`() {
        // A body that would be degraded under a full check, and a latency budget of zero.
        val body = """{"status":"degraded","version":"9","stats":{"queueDepth":1}}"""

        val result = runCheck(HttpStatusCode.OK, body, CheckType.SIMPLE, degradedAfterMillis = 0)

        assertEquals(Status.UP, result.status)
        assertEquals(200, result.httpStatus)
        assertNull(result.version)
        assertNull(result.error)
        assertTrue(result.stats.isEmpty())
    }

    @Test
    fun `a simple check is down on anything but a 200`() {
        val result = runCheck(HttpStatusCode.Found, "OK", CheckType.SIMPLE)

        assertEquals(Status.DOWN, result.status)
        assertEquals("Expected 200, got 302", result.error)
    }

    @Test
    fun `non json responses still check out`() {
        val result = runCheck(HttpStatusCode.OK, "pong")

        assertEquals(Status.UP, result.status)
        assertTrue(result.stats.isEmpty())
        assertNull(result.version)
    }

    private fun runCheck(
        status: HttpStatusCode,
        body: String,
        type: CheckType = CheckType.FULL,
        degradedAfterMillis: Long = 60_000,
    ): CheckResult {
        val engine = MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val config = HealthConfig(
            defaults = EndpointDefaults(degradedAfterMillis = degradedAfterMillis),
            endpoints = listOf(EndpointConfig(name = "Mock", url = "http://mock/health", type = type)),
        )
        val monitor = HealthMonitor(config, HttpClient(engine) { expectSuccess = false })
        return try {
            runBlocking { monitor.check(config.resolveEndpoints().single()) }
        } finally {
            monitor.stop()
        }
    }
}
