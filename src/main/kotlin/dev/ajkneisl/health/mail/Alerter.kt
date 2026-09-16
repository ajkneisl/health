package dev.ajkneisl.health.mail

import dev.ajkneisl.health.config.EmailConfig
import dev.ajkneisl.health.monitor.DashboardSnapshot
import dev.ajkneisl.health.monitor.EndpointSnapshot
import dev.ajkneisl.health.monitor.Status
import dev.ajkneisl.health.monitor.StatusChangeListener
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Turns status transitions into email.
 *
 * Mails once an endpoint has been [Status.DOWN] or [Status.DEGRADED] for
 * [EmailConfig.alertAfterChecks] checks in a row (and again if it moves between the two after
 * that), on recovery from an outage that was mailed about when [EmailConfig.notifyOnRecovery] is
 * set, and once a day with a report covering every endpoint.
 * Repeat alerts for the same endpoint and status inside [EmailConfig.cooldownMinutes] are dropped,
 * so a flapping endpoint cannot flood the recipients.
 */
class Alerter(
    private val config: EmailConfig,
    private val mailer: Mailer,
    private val now: () -> Long = System::currentTimeMillis,
) : StatusChangeListener {

    private val log = LoggerFactory.getLogger(Alerter::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val zone = config.dailyReport.zoneId()

    private val lastAlert = ConcurrentHashMap<String, Long>()

    /** The status each endpoint was in before its current run of failures began. */
    private val beforeOutage = ConcurrentHashMap<String, Status>()

    /** The status last mailed about for each endpoint's current outage. */
    private val alerted = ConcurrentHashMap<String, Status>()

    private var dailyReportJob: Job? = null

    override suspend fun onStatusChange(endpoint: EndpointSnapshot, previous: Status) {
        if (endpoint.status.failing) {
            if (!previous.failing) beforeOutage[endpoint.id] = previous
            return
        }

        beforeOutage.remove(endpoint.id)
        val wasAlerted = alerted.remove(endpoint.id) != null
        if (endpoint.status == Status.UP && config.notifyOnRecovery && wasAlerted) {
            alert(endpoint, previous)
        }
    }

    override suspend fun onCheck(endpoint: EndpointSnapshot) {
        if (!endpoint.status.failing) return
        if (endpoint.consecutiveFailures < config.alertAfterChecks.coerceAtLeast(1)) return

        val previous = alerted.put(endpoint.id, endpoint.status)
        if (previous == endpoint.status) return

        alert(endpoint, previous ?: beforeOutage[endpoint.id] ?: Status.UP)
    }

    private suspend fun alert(endpoint: EndpointSnapshot, previous: Status) {
        val key = "${endpoint.id}:${endpoint.status}"
        val cooldown = Duration.ofMinutes(config.cooldownMinutes).toMillis()
        val previousAlert = lastAlert[key]
        if (previousAlert != null && now() - previousAlert < cooldown) {
            log.debug(
                "Suppressing {} alert for {} — inside the cooldown",
                endpoint.status,
                endpoint.name
            )
            return
        }
        lastAlert[key] = now()

        send(Emails.alert(endpoint, previous, config, zone))
    }

    /** Schedules the daily report at the configured local time. */
    fun startDailyReport(snapshotProvider: () -> DashboardSnapshot) {
        if (!config.dailyReport.enabled) return

        val at = config.dailyReport.localTime()
        dailyReportJob = scope.launch {
            while (isActive) {
                val next = nextRun(ZonedDateTime.now(zone), at)
                log.info("Next daily status report at {}", next)
                delay(Duration.between(ZonedDateTime.now(zone), next).toMillis().coerceAtLeast(1))
                sendDailyReport(snapshotProvider())
            }
        }
    }

    suspend fun sendDailyReport(snapshot: DashboardSnapshot) {
        send(Emails.dailyReport(snapshot, config, zone))
    }

    fun stop() {
        dailyReportJob = null
        scope.cancel()
        mailer.close()
    }

    private suspend fun send(message: EmailMessage) {
        try {
            mailer.send(message)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            log.error("Could not send \"{}\"", message.subject, failure)
        }
    }

    private val Status.failing: Boolean
        get() = this == Status.DOWN || this == Status.DEGRADED

    internal companion object {
        /** The next occurrence of [at], today if it is still ahead of [from], otherwise tomorrow. */
        fun nextRun(from: ZonedDateTime, at: java.time.LocalTime): ZonedDateTime {
            val today = from.with(at)
            return if (today.isAfter(from)) today else today.plusDays(1)
        }
    }
}
