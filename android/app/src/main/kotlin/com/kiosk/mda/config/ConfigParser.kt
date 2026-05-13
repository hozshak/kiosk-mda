package com.kiosk.mda.config

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Parst die Kiosk-Config-XML in ein KioskConfig-Objekt.
 * Schema siehe README. Tolerant gegenüber fehlenden Optional-Feldern.
 */
object ConfigParser {

    fun parse(xml: String): Result<KioskConfig> = runCatching {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var version = 1
        var environment = "prod"
        var browser: BrowserConfig? = null
        var device: DeviceConfig? = null
        var admin: AdminConfig? = null
        var server: ServerConfig? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "config" -> {
                        version = parser.getAttributeValue(null, "version")?.toIntOrNull() ?: 1
                        environment = parser.getAttributeValue(null, "environment") ?: "prod"
                    }
                    "browser" -> browser = parseBrowser(parser)
                    "device" -> device = parseDevice(parser)
                    "admin" -> admin = parseAdmin(parser)
                    "server" -> server = parseServer(parser)
                }
            }
            event = parser.next()
        }

        KioskConfig(
            version = version,
            environment = environment,
            browser = browser ?: KioskConfig.DEFAULT.browser,
            device = device ?: KioskConfig.DEFAULT.device,
            admin = admin ?: KioskConfig.DEFAULT.admin,
            server = server ?: KioskConfig.DEFAULT.server
        )
    }

    private fun parseBrowser(parser: XmlPullParser): BrowserConfig {
        var startUrl = "about:blank"
        var clearCache = false
        var jsEnabled = true
        var oskEnabled = false
        var oskToggleVisible = true
        val bookmarks = mutableListOf<Bookmark>()

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "browser")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "startUrl" -> startUrl = readText(parser).trim()
                    "clearCacheOnExit" -> clearCache = readText(parser).trim().toBoolean()
                    "javaScriptEnabled" -> jsEnabled = readText(parser).trim().toBoolean()
                    "oskEnabled" -> oskEnabled = readText(parser).trim().toBoolean()
                    // Backwards-compat: alter <oskMode>on/auto = on, off = off
                    "oskMode" -> {
                        val m = readText(parser).trim().lowercase()
                        oskEnabled = m == "on" || m == "auto" || m == "true"
                    }
                    "oskToggleVisible" -> oskToggleVisible = readText(parser).trim().toBoolean()
                    "bookmarks" -> bookmarks.addAll(parseBookmarks(parser))
                }
            }
            event = parser.next()
        }
        return BrowserConfig(startUrl, bookmarks, clearCache, jsEnabled, oskEnabled, oskToggleVisible)
    }

    private fun parseBookmarks(parser: XmlPullParser): List<Bookmark> {
        val list = mutableListOf<Bookmark>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "bookmarks")) {
            if (event == XmlPullParser.START_TAG && parser.name == "bookmark") {
                val name = parser.getAttributeValue(null, "name") ?: ""
                val url = parser.getAttributeValue(null, "url") ?: ""
                if (name.isNotBlank() && url.isNotBlank()) {
                    list += Bookmark(name, url)
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun parseDevice(parser: XmlPullParser): DeviceConfig {
        var orientation = Orientation.AUTO
        var timeout = 0
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "device")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "orientation" -> orientation = Orientation.fromString(readText(parser).trim())
                    "displayTimeout" -> timeout = readText(parser).trim().toIntOrNull() ?: 0
                }
            }
            event = parser.next()
        }
        return DeviceConfig(orientation, timeout)
    }

    private fun parseAdmin(parser: XmlPullParser): AdminConfig {
        var hash = ""
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "admin")) {
            if (event == XmlPullParser.START_TAG && parser.name == "pinHash") {
                hash = readText(parser).trim().lowercase()
            }
            event = parser.next()
        }
        return AdminConfig(hash)
    }

    private fun parseServer(parser: XmlPullParser): ServerConfig {
        var url = ""
        var poll = 300
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "server")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "configUrl" -> url = readText(parser).trim()
                    "pollIntervalSec" -> poll = readText(parser).trim().toIntOrNull() ?: 300
                }
            }
            event = parser.next()
        }
        return ServerConfig(url, poll)
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text ?: ""
            parser.next()
        }
        return result
    }
}
