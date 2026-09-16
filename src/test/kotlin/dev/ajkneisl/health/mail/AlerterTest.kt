package dev.ajkneisl.health.mail

import dev.ajkneisl.health.config.EmailConfig
import dev.ajkneisl.health.monitor.CheckResult
import dev.ajkneisl.health.monitor.DashboardSnapshot
import dev.ajkneisl.health.monitor.EndpointSnapshot
import dev.ajkneisl.health.monitor.Stat
import dev.ajkneisl.health.monitor.Status

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class AlerterTest {

    private class FakeMailer : Mailer {
        val sent = mutableListOf<EmailMessage>()

        override suspend fun send(message: EmailMessage) {
            sent += message
        }
    }

    private val config = EmailConfig(
        enabled = true,
        from = "health@example.com",
        to = listOf("ops@example.com"),
        cooldownMinutes = 15,
    )

    private var clock = 0L

    private fun alerter(config: EmailConfig = this.config, mailer: FakeMailer) =
        Alerter(config, mailer) { clock }

    /** Delivers a check the way [dev.ajkneisl.health.monitor.HealthMonitor] does. */
    private suspend fun Alerter.check(endpoint: EndpointSnapshot, previous: Status? = null) {
        if (previous != null && previous != endpoint.status) onStatusChange(endpoint, previous)
        onCheck(endpoint)
    }

    @Test
    fun `failure sends an email`() = runBlocking {
        val mailer = FakeMailer()

        alerter(mailer = mailer).check(snapshot(Status.DOWN, error = "Connection refused"), Status.UP)

        val message = mailer.sent.single()
        assertEquals("[health] Public API is down", message.subject)
        assertContains(message.text, "Connection refused")
        assertContains(message.text, "Status: Down (was Operational)")
        assertContains(message.html, "Public API is down")
    }

    @Test
    fun `degradation sends an email and carries the stats`() = runBlocking {
        val mailer = FakeMailer()
        val degraded = snapshot(
            Status.DEGRADED,
            error = "Slower than 250ms",
            stats = listOf(Stat("Queue depth", "812"), Stat("Workers", "8")),
        )

        alerter(mailer = mailer).check(degraded, Status.UP)

        val message = mailer.sent.single()
        assertEquals("[health] Public API is degraded", message.subject)
        assertContains(message.text, "Queue depth: 812")
        assertContains(message.html, "Queue depth: 812")
    }

    @Test
    fun `nothing is sent until enough failing checks in a row`() = runBlocking {
        val mailer = FakeMailer()
        val alerter = alerter(mailer = mailer)

        alerter.check(snapshot(Status.DOWN, failures = 1), Status.UP)
        (2..4).forEach { alerter.check(snapshot(Status.DOWN, failures = it)) }
        assertTrue(mailer.sent.isEmpty())

        alerter.check(snapshot(Status.DOWN, failures = 5))
        alerter.check(snapshot(Status.DOWN, failures = 6))
        assertEquals("[health] Public API is down", mailer.sent.single().subject)
        assertContains(mailer.sent.single().text, "Status: Down (was Operational)")
    }

    @Test
    fun `a short blip is never mailed about, even on recovery`() = runBlocking {
        val mailer = FakeMailer()
        val alerter = alerter(config.copy(notifyOnRecovery = true), mailer)

        alerter.check(snapshot(Status.DEGRADED, failures = 3), Status.UP)
        alerter.check(snapshot(Status.UP), Status.DEGRADED)

        assertTrue(mailer.sent.isEmpty())
    }

    @Test
    fun `recovery is silent unless it is asked for`() = runBlocking {
        val quiet = FakeMailer()
        alerter(mailer = quiet).apply {
            check(snapshot(Status.DOWN), Status.UP)
            check(snapshot(Status.UP), Status.DOWN)
        }
        assertEquals(listOf("[health] Public API is down"), quiet.sent.map { it.subject })

        val loud = FakeMailer()
        alerter(config.copy(notifyOnRecovery = true), loud).apply {
            check(snapshot(Status.DOWN), Status.UP)
            check(snapshot(Status.UP), Status.DOWN)
        }
        assertEquals("[health] Public API recovered", loud.sent.last().subject)
    }

    @Test
    fun `a flapping endpoint cannot flood the recipients`() = runBlocking {
        val mailer = FakeMailer()
        val alerter = alerter(mailer = mailer)

        alerter.check(snapshot(Status.DOWN), Status.UP)
        alerter.check(snapshot(Status.UP), Status.DOWN)
        clock += 60_000 // one minute later, still inside the 15 minute cooldown
        alerter.check(snapshot(Status.DOWN), Status.UP)
        alerter.check(snapshot(Status.UP), Status.DOWN)
        assertEquals(1, mailer.sent.size)

        clock += 15 * 60_000
        alerter.check(snapshot(Status.DOWN), Status.UP)
        assertEquals(2, mailer.sent.size)
    }

    @Test
    fun `the cooldown is per endpoint and status`() = runBlocking {
        val mailer = FakeMailer()
        val alerter = alerter(mailer = mailer)

        alerter.check(snapshot(Status.DOWN), Status.UP)
        alerter.check(snapshot(Status.DOWN, id = "worker", name = "Worker"), Status.UP)
        alerter.check(snapshot(Status.DEGRADED), Status.DOWN)

        assertEquals(
            listOf("[health] Public API is down", "[health] Worker is down", "[health] Public API is degraded"),
            mailer.sent.map { it.subject },
        )
    }

    @Test
    fun `the daily report covers every endpoint`() = runBlocking {
        val mailer = FakeMailer()
        val dashboard = DashboardSnapshot(
            title = "Health",
            generatedAt = 1_700_000_000_000,
            refreshSeconds = 15,
            endpoints = listOf(
                snapshot(Status.UP),
                snapshot(Status.DOWN, id = "worker", name = "Worker", error = "Timed out after 5000ms"),
                snapshot(Status.DEGRADED, id = "db", name = "Database", group = "Storage"),
            ),
        )

        alerter(mailer = mailer).sendDailyReport(dashboard)

        val message = mailer.sent.single()
        assertEquals("[health] Daily status report — 1 up, 1 degraded, 1 down of 3", message.subject)
        listOf("Public API", "Worker", "Database", "Timed out after 5000ms", "Storage").forEach {
            assertContains(message.text, it)
            assertContains(message.html, it)
        }
    }

    @Test
    fun `the report is scheduled for the next occurrence of the configured time`() {
        val zone = ZoneId.of("America/Chicago")
        val at = LocalTime.of(8, 0)

        val beforeToday = ZonedDateTime.of(2026, 9, 14, 6, 30, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 9, 14, 8, 0, 0, 0, zone),
            Alerter.nextRun(beforeToday, at),
        )

        val afterToday = ZonedDateTime.of(2026, 9, 14, 9, 30, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 9, 15, 8, 0, 0, 0, zone),
            Alerter.nextRun(afterToday, at),
        )
    }

    @Test
    fun `an unconfigured mailer only logs`() = runBlocking {
        val mailer = Mailer.create(EmailConfig(enabled = true, from = "", to = emptyList()))
        assertIs<LoggingMailer>(mailer)
        mailer.send(EmailMessage("subject", "<p>html</p>", "text"))
    }

    private fun snapshot(
        status: Status,
        id: String = "public-api",
        name: String = "Public API",
        group: String = "Core",
        error: String? = null,
        stats: List<Stat> = emptyList(),
        failures: Int = if (status == Status.DOWN || status == Status.DEGRADED) 5 else 0,
    ) = EndpointSnapshot(
        id = id,
        name = name,
        url = "http://localhost:8080/demo/api",
        method = "GET",
        group = group,
        intervalSeconds = 15,
        status = status,
        latest = CheckResult(
            status = status,
            checkedAt = 1_700_000_000_000,
            latencyMillis = 42,
            httpStatus = if (status == Status.DOWN) 503 else 200,
            error = error,
            version = "2.7.1",
            stats = stats,
        ),
        uptimePercent = 96.5,
        avgLatencyMillis = 40,
        totalChecks = 20,
        totalFailures = 1,
        statusSince = 1_700_000_000_000,
        consecutiveFailures = failures,
    )
}
