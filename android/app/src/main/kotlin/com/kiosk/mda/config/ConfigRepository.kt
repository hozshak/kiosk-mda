package com.kiosk.mda.config

import android.content.Context
import com.kiosk.mda.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lädt Config aus dem Cache (sofort) und im Hintergrund vom Server.
 * Singleton via Application-Context.
 */
class ConfigRepository private constructor(context: Context) {

    private val store = ConfigStore(context.applicationContext)

    private val _config = MutableStateFlow(loadInitialConfig())
    val config: StateFlow<KioskConfig> = _config.asStateFlow()

    private fun loadInitialConfig(): KioskConfig {
        val xml = store.loadXml()
        val base = if (xml != null) {
            ConfigParser.parse(xml).getOrDefault(KioskConfig.DEFAULT)
        } else {
            KioskConfig.DEFAULT
        }
        return applyTestUrlOverride(base)
    }

    private fun applyTestUrlOverride(base: KioskConfig): KioskConfig {
        val testUrl = store.testStartUrl()?.takeIf { it.isNotBlank() } ?: return base
        return base.copy(browser = base.browser.copy(startUrl = testUrl))
    }

    fun testStartUrl(): String? = store.testStartUrl()

    fun setTestStartUrl(url: String?) {
        store.setTestStartUrl(url)
        _config.value = applyTestUrlOverride(
            run {
                val xml = store.loadXml()
                if (xml != null) ConfigParser.parse(xml).getOrDefault(KioskConfig.DEFAULT)
                else KioskConfig.DEFAULT
            }
        )
    }

    fun environment(): String = store.environment()

    fun setEnvironment(env: String) = store.setEnvironment(env)

    fun overrideUrl(): String? = store.overrideConfigUrl()

    fun setOverrideUrl(url: String?) = store.setOverrideConfigUrl(url)

    /**
     * Bestimmt die effektive Config-URL:
     * 1. Override aus Admin-Menü
     * 2. URL aus geladener Config (falls bereits einmal gepullt)
     * 3. BuildConfig-Default
     */
    fun effectiveConfigUrl(): String {
        store.overrideConfigUrl()?.takeIf { it.isNotBlank() }?.let { return it }
        _config.value.server.configUrl.takeIf { it.isNotBlank() }?.let { return it }
        return BuildConfig.DEFAULT_CONFIG_URL
    }

    /**
     * Holt die Config vom Server. Nutzt If-None-Match für ETag-Caching.
     * @return true wenn neue Config geladen wurde, false bei 304/Fehler.
     */
    suspend fun fetchRemote(): SyncResult = withContext(Dispatchers.IO) {
        val url = effectiveConfigUrl()
        if (url.isBlank()) return@withContext SyncResult.NoUrl

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/xml")
            store.lastEtag()?.let { setRequestProperty("If-None-Match", it) }
        }

        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val etag = conn.getHeaderField("ETag")
                    val parsed = ConfigParser.parse(body).getOrElse {
                        return@withContext SyncResult.ParseError(it.message ?: "parse failed")
                    }
                    store.saveXml(body, etag)
                    val merged = applyTestUrlOverride(parsed)
                    _config.value = merged
                    SyncResult.Updated(merged)
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> SyncResult.NotModified
                else -> SyncResult.HttpError(code)
            }
        } catch (e: Exception) {
            SyncResult.NetworkError(e.message ?: "network failed")
        } finally {
            conn.disconnect()
        }
    }

    sealed class SyncResult {
        data class Updated(val config: KioskConfig) : SyncResult()
        object NotModified : SyncResult()
        object NoUrl : SyncResult()
        data class HttpError(val code: Int) : SyncResult()
        data class ParseError(val msg: String) : SyncResult()
        data class NetworkError(val msg: String) : SyncResult()
    }

    companion object {
        @Volatile private var instance: ConfigRepository? = null

        fun get(context: Context): ConfigRepository =
            instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
    }
}
