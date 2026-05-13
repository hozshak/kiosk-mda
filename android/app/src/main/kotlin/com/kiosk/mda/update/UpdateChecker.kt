package com.kiosk.mda.update

import android.content.Context
import android.util.Log
import com.kiosk.mda.BuildConfig
import com.kiosk.mda.config.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Prüft ob auf dem Config-Server eine neuere APK liegt und lädt sie ggf. herunter.
 * Server-Endpoint: <configHost>/apk/latest.json
 *   { available: true|false, versionCode, versionName, fileName, sha256, size, uploadedAt }
 */
class UpdateChecker(private val context: Context) {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val fileName: String,
        val sha256: String,
        val size: Long,
        val downloadUrl: String,
    )

    /** Liefert UpdateInfo wenn Server eine neuere Version hat - sonst null. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val baseUrl = deriveServerBase() ?: return@withContext null
        val metaUrl = "$baseUrl/apk/latest.json"
        try {
            val conn = (URL(metaUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                if (conn.responseCode != 200) return@withContext null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                if (!json.optBoolean("available", false)) return@withContext null

                val versionCode = json.getInt("versionCode")
                if (versionCode <= BuildConfig.VERSION_CODE) return@withContext null

                UpdateInfo(
                    versionCode = versionCode,
                    versionName = json.getString("versionName"),
                    fileName = json.getString("fileName"),
                    sha256 = json.getString("sha256"),
                    size = json.getLong("size"),
                    downloadUrl = "$baseUrl/apk/${json.getString("fileName")}",
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            null
        }
    }

    /** Lädt die APK in den App-Cache. Liefert die Datei oder null bei Fehler. */
    suspend fun download(info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        try {
            val targetDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(targetDir, info.fileName)

            // Falls existiert und Hash stimmt: nicht neu downloaden
            if (target.exists() && target.length() == info.size && sha256Of(target) == info.sha256) {
                Log.i(TAG, "APK already cached: ${target.absolutePath}")
                return@withContext target
            }
            target.delete()

            val conn = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode != 200) {
                    Log.w(TAG, "download HTTP ${conn.responseCode}")
                    return@withContext null
                }
                FileOutputStream(target).use { out ->
                    conn.inputStream.copyTo(out, bufferSize = 32 * 1024)
                }
            } finally {
                conn.disconnect()
            }

            val actualSha = sha256Of(target)
            if (actualSha != info.sha256) {
                Log.w(TAG, "SHA-256 mismatch: expected=${info.sha256} got=$actualSha")
                target.delete()
                return@withContext null
            }
            Log.i(TAG, "APK downloaded to ${target.absolutePath} (${target.length()} bytes)")
            target
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            null
        }
    }

    private fun deriveServerBase(): String? {
        val repo = ConfigRepository.get(context)
        val configUrl = repo.effectiveConfigUrl().takeIf { it.isNotBlank() } ?: return null
        // Strip /config/{env} - alles vor /config/
        val idx = configUrl.indexOf("/config/")
        return if (idx > 0) configUrl.substring(0, idx) else null
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "KioskUpdate"
    }
}
