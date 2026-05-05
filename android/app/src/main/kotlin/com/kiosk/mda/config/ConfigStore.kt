package com.kiosk.mda.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistiert die zuletzt geladene XML-Config + ETag verschlüsselt.
 * Nutzt EncryptedSharedPreferences damit PIN-Hash nicht im Klartext im File-System liegt.
 */
class ConfigStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "kiosk_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveXml(xml: String, etag: String?) {
        prefs.edit()
            .putString(KEY_XML, xml)
            .putString(KEY_ETAG, etag)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun loadXml(): String? = prefs.getString(KEY_XML, null)

    fun lastEtag(): String? = prefs.getString(KEY_ETAG, null)

    fun lastUpdatedMs(): Long = prefs.getLong(KEY_UPDATED, 0L)

    fun setEnvironment(env: String) {
        prefs.edit().putString(KEY_ENV, env).apply()
    }

    fun environment(): String = prefs.getString(KEY_ENV, "prod") ?: "prod"

    fun setOverrideConfigUrl(url: String?) {
        prefs.edit().putString(KEY_URL_OVERRIDE, url).apply()
    }

    fun overrideConfigUrl(): String? = prefs.getString(KEY_URL_OVERRIDE, null)

    /** Lokale Test-Start-URL die auch ohne Server-Config greift. */
    fun setTestStartUrl(url: String?) {
        prefs.edit().putString(KEY_TEST_START_URL, url).apply()
    }

    fun testStartUrl(): String? = prefs.getString(KEY_TEST_START_URL, null)

    companion object {
        private const val KEY_XML = "config_xml"
        private const val KEY_ETAG = "config_etag"
        private const val KEY_UPDATED = "config_updated_ms"
        private const val KEY_ENV = "environment"
        private const val KEY_URL_OVERRIDE = "config_url_override"
        private const val KEY_TEST_START_URL = "test_start_url"
    }
}
