package dev.ajkneisl.health.monitor

import dev.ajkneisl.health.config.CheckType
import dev.ajkneisl.health.config.Endpoint
import dev.ajkneisl.health.config.HealthConfig
import dev.ajkneisl.health.config.resolveEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HealthMonitor(
    val config: HealthConfig,
    private val client: HttpClient = defaultClient(),
    private val listener: StatusChangeListener? = null,
) {
    private val log = LoggerFactory.getLogger(HealthMonitor::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val endpoints = config.resolveEndpoints()
    private val states = endpoints.associate { it.id to EndpointState(it) }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _version = MutableStateFlow(0L)

    /** Bumped whenever an endpoint changes status, so connected dashboards can refresh. */
    val version: StateFlow<Long> = _version

    fun start() {
        endpoints.forEachIndexed { index, endpoint ->
            scope.launch {
                // Stagger the initial checks so a large config does not fire everything at once.
                delay((index * 150L).milliseconds)
                while (isActive) {
                    runCheck(endpoint)
                    delay((endpoint.intervalSeconds * 1000).milliseconds)
                }
            }
        }
        log.info("Monitoring {} endpoint(s)", endpoints.size)
    }

    fun stop() {
        scope.cancel()
        client.close()
    }

    /** Checks every endpoint right now, outside of its normal schedule. */
    suspend fun checkAll() {
        endpoints.map { scope.async { runCheck(it) } }.awaitAll()
    }

    fun snapshot(): DashboardSnapshot = DashboardSnapshot(
        title = config.title,
        generatedAt = System.currentTimeMillis(),
        refreshSeconds = config.refreshSeconds,
        endpoints = endpoints.mapNotNull { states[it.id]?.snapshot() },
    )

    private suspend fun runCheck(endpoint: Endpoint) {
        val result = check(endpoint)
        val state = states[endpoint.id] ?: return
        val previous = state.record(result)

        if (previous != null) {
            log.info(
                "{} is {} ({})",
                endpoint.name,
                result.status,
                result.error ?: "${result.latencyMillis}ms"
            )

            _version.value += 1
        }

        if (listener == null) return
        try {
            val snapshot = state.snapshot()
            if (previous != null) listener.onStatusChange(snapshot, previous)
            listener.onCheck(snapshot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            log.error("Status change listener failed for {}", endpoint.name, failure)
        }
    }

    internal suspend fun check(endpoint: Endpoint): CheckResult {
        val startedAt = System.nanoTime()
        fun elapsed() = (System.nanoTime() - startedAt) / 1_000_000

        return try {
            withTimeout(endpoint.timeoutMillis.milliseconds) {
                val response: HttpResponse = client.request(endpoint.url) {
                    method = HttpMethod.parse(endpoint.method)
                    endpoint.headers.forEach { (name, value) -> header(name, value) }
                }
                val body = response.bodyAsText()
                interpret(endpoint, response.status.value, body, elapsed())
            }
        } catch (_: TimeoutCancellationException) {
            CheckResult(
                status = Status.DOWN,
                checkedAt = System.currentTimeMillis(),
                latencyMillis = elapsed(),
                error = "Timed out after ${endpoint.timeoutMillis}ms",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            CheckResult(
                status = Status.DOWN,
                checkedAt = System.currentTimeMillis(),
                latencyMillis = elapsed(),
                error = failure.message ?: failure::class.simpleName ?: "Request failed",
            )
        }
    }

    private fun interpret(
        endpoint: Endpoint,
        httpStatus: Int,
        body: String,
        latency: Long
    ): CheckResult {
        if (endpoint.type == CheckType.SIMPLE) {
            return CheckResult(
                status = if (httpStatus == 200) Status.UP else Status.DOWN,
                checkedAt = System.currentTimeMillis(),
                latencyMillis = latency,
                httpStatus = httpStatus,
                error = "Expected 200, got $httpStatus".takeIf { httpStatus != 200 },
            )
        }

        val document = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val reported =
            (document?.get("status") as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
        val version =
            (document?.get("version") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        val status = when {
            httpStatus !in endpoint.expectedStatuses -> Status.DOWN
            reported in setOf("down", "error", "unhealthy", "fail", "failed") -> Status.DOWN
            reported in setOf("degraded", "warn", "warning", "partial") -> Status.DEGRADED
            latency > endpoint.degradedAfterMillis -> Status.DEGRADED
            else -> Status.UP
        }

        val error = when {
            httpStatus !in endpoint.expectedStatuses ->
                "Expected ${endpoint.expectedStatuses.joinToString("/")}, got $httpStatus"

            status == Status.DOWN -> "Endpoint reported status \"$reported\""
            status == Status.DEGRADED && reported != null && latency <= endpoint.degradedAfterMillis ->
                "Endpoint reported status \"$reported\""

            status == Status.DEGRADED -> "Slower than ${endpoint.degradedAfterMillis}ms"
            else -> null
        }

        return CheckResult(
            status = status,
            checkedAt = System.currentTimeMillis(),
            latencyMillis = latency,
            httpStatus = httpStatus,
            error = error,
            version = version,
            stats = extractStats(document, endpoint.statsPath),
        )
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            followRedirects = true
        }
    }
}
