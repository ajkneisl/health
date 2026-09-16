package dev.ajkneisl.health.mail

import dev.ajkneisl.health.config.EmailConfig
import org.slf4j.LoggerFactory

class LoggingMailer(private val config: EmailConfig) : Mailer {
    private val log = LoggerFactory.getLogger(this::class.java)

    override suspend fun send(message: EmailMessage) {
        val reason =
            when {
                !config.enabled -> "email is disabled"
                config.from.isBlank() -> "no \"from\" address is configured"
                else -> "no recipients are configured"
            }

        log.info("Not sending \"{}\" — {}", message.subject, reason)
        log.debug("Message body:\n{}", message.text)
    }
}
