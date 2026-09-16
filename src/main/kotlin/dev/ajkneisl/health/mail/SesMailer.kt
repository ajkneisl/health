package dev.ajkneisl.health.mail

import dev.ajkneisl.health.config.EmailConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest

/** Sends through Amazon SES v2. Credentials come from the default AWS provider chain. */
class SesMailer(private val config: EmailConfig) : Mailer {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val clientHolder = lazy {
        SesV2Client.builder().region(Region.of(config.region)).build()
    }

    override suspend fun send(message: EmailMessage) {
        val request = SendEmailRequest.builder()
            .fromEmailAddress(config.from)
            .destination(Destination.builder().toAddresses(config.to).build())
            .content(
                EmailContent.builder()
                    .simple(
                        Message.builder()
                            .subject(content(message.subject))
                            .body(
                                Body.builder()
                                    .html(content(message.html))
                                    .text(content(message.text))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val response = withContext(Dispatchers.IO) { clientHolder.value.sendEmail(request) }
        log.info(
            "Sent \"{}\" to {} (SES message {})",
            message.subject,
            config.to,
            response.messageId()
        )
    }

    override fun close() {
        if (clientHolder.isInitialized()) clientHolder.value.close()
    }

    private fun content(data: String): Content =
        Content.builder().data(data).charset("UTF-8").build()
}
