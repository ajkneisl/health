package dev.ajkneisl.health.mail

import dev.ajkneisl.health.config.EmailConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2Client
import java.util.Base64
import java.util.UUID
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.RawMessage
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest

/**
 * Sends through Amazon SES v2. Credentials come from the default AWS provider chain.
 *
 * Messages go out as raw MIME, so the IAM identity needs `ses:SendRawEmail` rather than
 * `ses:SendEmail`.
 */
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
                    .raw(
                        RawMessage.builder()
                            .data(SdkBytes.fromUtf8String(mime(config, message)))
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

    internal companion object {
        private val mimeEncoder = Base64.getMimeEncoder(76, "\r\n".toByteArray())

        /** Renders [message] as a `multipart/alternative` MIME document with text and HTML parts. */
        fun mime(
            config: EmailConfig,
            message: EmailMessage,
            boundary: String = "health-${UUID.randomUUID()}",
        ): String {
            fun part(contentType: String, body: String) =
                "--$boundary\r\n" +
                    "Content-Type: $contentType; charset=UTF-8\r\n" +
                    "Content-Transfer-Encoding: base64\r\n\r\n" +
                    mimeEncoder.encodeToString(body.toByteArray()) + "\r\n"

            return "From: ${config.from}\r\n" +
                "To: ${config.to.joinToString(", ")}\r\n" +
                "Subject: ${encodedWord(message.subject)}\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/alternative; boundary=\"$boundary\"\r\n\r\n" +
                part("text/plain", message.text) +
                part("text/html", message.html) +
                "--$boundary--\r\n"
        }

        /**
         * RFC 2047 encodes a header value. Each encoded word holds whole characters and stays
         * under the 75 character limit, with continuation lines folded.
         */
        private fun encodedWord(value: String): String {
            val words = mutableListOf<String>()
            val chunk = StringBuilder()
            var bytes = 0
            fun flush() {
                if (chunk.isEmpty()) return
                val encoded = Base64.getEncoder().encodeToString(chunk.toString().toByteArray())
                words += "=?UTF-8?B?$encoded?="
                chunk.clear()
                bytes = 0
            }
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                val text = String(Character.toChars(codePoint))
                val size = text.toByteArray().size
                if (bytes + size > 45) flush()
                chunk.append(text)
                bytes += size
                index += text.length
            }
            flush()
            return words.joinToString("\r\n ")
        }
    }
}
