package dev.ajkneisl.health.mail

import dev.ajkneisl.health.config.EmailConfig
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SesMailerTest {
    private val config =
        EmailConfig(enabled = true, from = "health@example.com", to = listOf("a@example.com", "b@example.com"))

    private fun decodeSubject(mime: String): String {
        val header = mime.substringAfter("Subject: ").substringBefore("\r\nMIME-Version")
        return Regex("=\\?UTF-8\\?B\\?([^?]+)\\?=").findAll(header).joinToString("") {
            String(Base64.getDecoder().decode(it.groupValues[1]))
        }
    }

    @Test
    fun `mime carries headers and both bodies`() {
        val message = EmailMessage("[health] Report — 10 up", "<p>héllo</p>", "héllo")
        val mime = SesMailer.mime(config, message, boundary = "b0")

        assertContains(mime, "From: health@example.com\r\n")
        assertContains(mime, "To: a@example.com, b@example.com\r\n")
        assertContains(mime, "Content-Type: multipart/alternative; boundary=\"b0\"\r\n\r\n")
        assertEquals(message.subject, decodeSubject(mime))

        val parts = mime.split("--b0").drop(1)
        assertEquals(3, parts.size)
        assertContains(parts[0], "Content-Type: text/plain; charset=UTF-8")
        assertContains(parts[1], "Content-Type: text/html; charset=UTF-8")
        fun body(part: String) =
            String(Base64.getMimeDecoder().decode(part.substringAfter("\r\n\r\n").trim()))
        assertEquals(message.text, body(parts[0]))
        assertEquals(message.html, body(parts[1]))
        assertEquals("--\r\n", parts[2])
    }

    @Test
    fun `long subjects are folded into short encoded words`() {
        val subject = "[health] " + "Ünïcödé — ".repeat(20)
        val mime = SesMailer.mime(config, EmailMessage(subject, "<p>x</p>", "x"), boundary = "b0")

        assertEquals(subject, decodeSubject(mime))
        val headerLines =
            mime.substringAfter("Subject: ").substringBefore("\r\nMIME-Version").split("\r\n ")
        assertTrue(headerLines.size > 1)
        assertTrue(headerLines.all { it.length <= 75 })
    }
}
