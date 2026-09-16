package dev.ajkneisl.health.web

import dev.ajkneisl.health.config.EndpointConfig
import dev.ajkneisl.health.config.HealthConfig
import dev.ajkneisl.health.healthModule
import dev.ajkneisl.health.monitor.DashboardSnapshot
import dev.ajkneisl.health.monitor.extractStats
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class DashboardRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val config = HealthConfig(
        title = "Test",
        endpoints = listOf(EndpointConfig(name = "Demo API", url = "http://localhost/demo/api", group = "Core")),
    )

    @Test
    fun `dashboard renders configured endpoints`() = testApplication {
        application { healthModule(config) }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)

        val html = response.bodyAsText()
        assertContains(html, "Demo API")
        assertContains(html, "Core")
        assertContains(html, "http://localhost/demo/api")
    }

    /**
     * The script is a plain asset now, so an editor reformatting it must not leave a templating
     * placeholder behind: that is a syntax error, and it silently kills every handler on the page.
     */
    @Test
    fun `the page ships a script with no placeholders left in it`() = testApplication {
        application { healthModule(config) }

        val html = client.get("/").bodyAsText()

        assertFalse(Regex("%[A-Z_]+%").containsMatchIn(DASHBOARD_SCRIPT))
        assertFalse(Regex("%[A-Z_]+%").containsMatchIn(DASHBOARD_CSS))
        assertContains(html, "data-refresh=\"${config.refreshSeconds}\"")
        assertContains(html, "document.getElementById('$CONTENT_ID')")
    }

    @Test
    fun `fragment returns just the content element for in-place updates`() = testApplication {
        application { healthModule(config) }

        val body = client.get("/fragment").bodyAsText()

        assertTrue(body.startsWith("<div id=\"dashboard\""))
        assertFalse(body.contains("<html"))
        assertContains(body, "Demo API")
    }

    @Test
    fun `details are rendered in a row the name toggles`() = testApplication {
        application { healthModule(config) }

        val body = client.get("/fragment").bodyAsText()

        assertContains(body, "<a href=\"#detail-demo-api\" data-endpoint=\"demo-api\">Demo API</a>")
        assertContains(body, "<tr class=\"detail\" id=\"detail-demo-api\" data-endpoint=\"demo-api\">")
    }

    @Test
    fun `api exposes the same snapshot as json`() = testApplication {
        application { healthModule(config) }

        val snapshot = json.decodeFromString(
            DashboardSnapshot.serializer(),
            client.get("/api/health").bodyAsText(),
        )

        assertEquals(1, snapshot.endpoints.size)
        assertEquals("Demo API", snapshot.endpoints.single().name)
    }

    @Test
    fun `demo endpoints report a version and a stats object`() = testApplication {
        application { healthModule(HealthConfig()) }

        val document = Json.parseToJsonElement(client.get("/demo/worker").bodyAsText()) as JsonObject

        assertNotNull(document["version"])
        assertTrue(extractStats(document, "stats").any { it.label == "Queue depth" })
    }
}
