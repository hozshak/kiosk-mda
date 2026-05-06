package com.kiosk.mda.push

import android.content.Context
import android.util.Log
import com.kiosk.mda.config.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Hält eine WebSocket-Verbindung zum Backend und triggert sofortige Config-Synchronisation
 * wenn der Server "config-updated" sendet.
 *
 * URL wird aus der konfigurierten Config-URL abgeleitet:
 *   https://host/config/prod  ->  wss://host/ws/prod
 *
 * Auto-Reconnect mit Backoff: 3s, 6s, 12s, ..., max 60s.
 */
class PushClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // unbegrenzt für WS
        .build()

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    @Volatile private var stopped = false

    fun start(scope: CoroutineScope) {
        this.scope = scope
        this.stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "client stop")
        webSocket = null
    }

    private fun deriveWsUrl(): String? {
        val repo = ConfigRepository.get(context)
        val configUrl = repo.effectiveConfigUrl().takeIf { it.isNotBlank() } ?: return null
        // /config/prod -> /ws/prod ; https -> wss
        return configUrl
            .replace(Regex("^http://"), "ws://")
            .replace(Regex("^https://"), "wss://")
            .replace("/config/", "/ws/")
    }

    private fun connect() {
        if (stopped) return
        val url = deriveWsUrl() ?: run {
            Log.i(TAG, "no config URL configured, skipping push connect")
            scheduleReconnect()
            return
        }
        Log.i(TAG, "connecting to $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "connected")
                attempt = 0
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.i(TAG, "msg: $text")
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closing $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed $code $reason")
                if (code != 1000) scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "config-updated" -> triggerConfigSync()
                "connected" -> Log.i(TAG, "server confirmed connection")
                "pong" -> {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad message: ${e.message}")
        }
    }

    private fun triggerConfigSync() {
        val repo = ConfigRepository.get(context)
        scope?.launch(Dispatchers.IO) {
            val result = repo.fetchRemote()
            Log.i(TAG, "push triggered sync: $result")
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        reconnectJob?.cancel()
        attempt = (attempt + 1).coerceAtMost(20)
        val delayMs = min(60_000L, 3_000L * (1L shl (attempt - 1).coerceAtMost(5)))
        Log.i(TAG, "reconnect in ${delayMs}ms (attempt $attempt)")
        reconnectJob = scope?.launch {
            delay(delayMs)
            connect()
        }
    }

    companion object {
        private const val TAG = "KioskPush"
    }
}
