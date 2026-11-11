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
 * Mails once an endpoint has been [Status.DOWN] for [EmailConfig.alertAfterChecks] failing checks
 * in a row, on recovery from an outage that was mailed about when [EmailConfig.notifyOnRecovery]
 * is set, and once a day with a report covering every endpoint.
 * Repeat alerts for the same endpoint and status inside [EmailConfig.cooldownMinutes] are dropped,
 * so a flapping endpoint cannot flood the recipients.
 *
 * [Status.DEGRADED] is quieter: a single email covers every degraded endpoint, and it is only sent
 * while more than [EmailConfig.degradedAlertAbove] of them have been degraded for
 * [EmailConfig.alertAfterChecks] checks in a row. It is sent once for as long as that stays true,
 * and when endpoints flap in and out, at most once per [EmailConfig.degradedCooldownMinutes].
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

    /** The endpoints whose current outage has been mailed about as down. */
    private val alerted = ConcurrentHashMap.newKeySet<String>()

    /** Endpoints that have been degraded for long enough to count. Guarded by its own lock. */
    private val degraded = LinkedHashMap<String, EndpointSnapshot>()

    /** Whether the current run of too many degraded endpoints has been mailed about. */
    private var degradedAlerted = false
    private var lastDegradedAlert: Long? = null

    private var dailyReportJob: Job? = null

    override suspend fun onStatusChange(endpoint: EndpointSnapshot, previous: Status) {
        if (endpoint.status.failing) {
            if (!previous.failing) beforeOutage[endpoint.id] = previous
            return
        }

        beforeOutage.remove(endpoint.id)
        val wasAlerted = alerted.remove(endpoint.id)
        if (endpoint.status == Status.UP && config.notifyOnRecovery && wasAlerted) {
            alert(endpoint, previous)
        }
    }

    override suspend fun onCheck(endpoint: EndpointSnapshot) {
        val sustained =
            endpoint.status.failing &&
                endpoint.consecutiveFailures >= config.alertAfterChecks.coerceAtLeast(1)

        degradedAlert(endpoint, sustained)?.let { send(Emails.degraded(it, config, zone)) }

        if (!sustained || endpoint.status != Status.DOWN) return
        if (!alerted.add(endpoint.id)) return

        alert(endpoint, beforeOutage[endpoint.id] ?: Status.UP)
    }

    /**
     * Records whether [endpoint] counts as degraded.
     *
     * @return the degraded endpoints to mail about, or null when there is nothing to send.
     */
    private fun degradedAlert(endpoint: EndpointSnapshot, sustained: Boolean) =
        synchronized(degraded) {
            if (sustained && endpoint.status == Status.DEGRADED) degraded[endpoint.id] = endpoint
            else degraded.remove(endpoint.id)

            if (degraded.size <= config.degradedAlertAbove) {
                degradedAlerted = false
                return@synchronized null
            }
            if (degradedAlerted) return@synchronized null

            // Inside the cooldown the alert is held back, not dropped: it goes out on a later
            // check if there are still too many degraded endpoints once the cooldown has passed.
            val cooldown = Duration.ofMinutes(config.degradedCooldownMinutes).toMillis()
            val last = lastDegradedAlert
            if (last != null && now() - last < cooldown) return@synchronized null

            degradedAlerted = true
            lastDegradedAlert = now()
            degraded.values.toList()
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
