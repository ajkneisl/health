package dev.ajkneisl.health.web

internal const val CONTENT_ID = "dashboard"

private fun readAsset(name: String): String {
    val stream =
        checkNotNull(DashboardAssets::class.java.getResourceAsStream("/web/$name")) {
            "missing dashboard asset: web/$name"
        }
    return stream.use { it.readBytes().decodeToString() }
}

private object DashboardAssets

internal val DASHBOARD_CSS: String by lazy { readAsset("dashboard.css") }
internal val DASHBOARD_SCRIPT: String by lazy { readAsset("dashboard.js") }
