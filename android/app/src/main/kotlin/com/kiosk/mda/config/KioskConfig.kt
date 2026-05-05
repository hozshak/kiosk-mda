package com.kiosk.mda.config

/**
 * In-Memory-Repräsentation der Kiosk-Config-XML.
 * Wird aus XML geparst (siehe ConfigParser).
 */
data class KioskConfig(
    val version: Int = 1,
    val environment: String = "prod",
    val browser: BrowserConfig,
    val device: DeviceConfig,
    val admin: AdminConfig,
    val server: ServerConfig
) {
    companion object {
        val DEFAULT = KioskConfig(
            browser = BrowserConfig(
                startUrl = "about:blank",
                bookmarks = emptyList(),
                clearCacheOnExit = false,
                javaScriptEnabled = true
            ),
            device = DeviceConfig(
                orientation = Orientation.AUTO,
                displayTimeoutSec = 0
            ),
            admin = AdminConfig(pinHash = ""),
            server = ServerConfig(
                configUrl = "",
                pollIntervalSec = 300
            )
        )
    }
}

data class BrowserConfig(
    val startUrl: String,
    val bookmarks: List<Bookmark>,
    val clearCacheOnExit: Boolean,
    val javaScriptEnabled: Boolean
)

data class Bookmark(
    val name: String,
    val url: String
)

data class DeviceConfig(
    val orientation: Orientation,
    /** 0 = always on, sonst Sekunden bis Standby */
    val displayTimeoutSec: Int
)

enum class Orientation {
    PORTRAIT, LANDSCAPE, AUTO;

    companion object {
        fun fromString(s: String?): Orientation = when (s?.lowercase()) {
            "portrait" -> PORTRAIT
            "landscape" -> LANDSCAPE
            else -> AUTO
        }
    }
}

data class AdminConfig(
    /** SHA-256-Hash hex-encoded */
    val pinHash: String
)

data class ServerConfig(
    val configUrl: String,
    val pollIntervalSec: Int
)
