package dev.ajkneisl.health.mail

import dev.ajkneisl.health.config.EmailConfig

interface Mailer {
    suspend fun send(message: EmailMessage)

    fun close() {}

    companion object {
        fun create(config: EmailConfig): Mailer =
            if (config.deliverable) SesMailer(config) else LoggingMailer(config)
    }
}
