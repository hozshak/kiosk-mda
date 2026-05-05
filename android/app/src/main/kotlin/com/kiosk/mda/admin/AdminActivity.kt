package com.kiosk.mda.admin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kiosk.mda.config.ConfigRepository
import com.kiosk.mda.databinding.ActivityAdminBinding
import kotlinx.coroutines.launch
import java.security.MessageDigest

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var repo: ConfigRepository
    private var pinVerified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository.get(this)

        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pinSection.visibility = android.view.View.VISIBLE
        binding.adminSection.visibility = android.view.View.GONE

        binding.btnUnlock.setOnClickListener { verifyPin() }
        binding.btnSync.setOnClickListener { triggerSync() }
        binding.btnExit.setOnClickListener { exitKiosk() }
        binding.btnSetUrl.setOnClickListener { saveOverrideUrl() }
        binding.btnEnvProd.setOnClickListener { setEnvironment("prod") }
        binding.btnEnvTest.setOnClickListener { setEnvironment("test") }

        binding.txtConfigUrl.setText(repo.overrideUrl() ?: "")
        binding.txtCurrentEnv.text = getString(
            com.kiosk.mda.R.string.admin_env_label,
            repo.environment()
        )
    }

    private fun verifyPin() {
        val entered = binding.txtPin.text.toString()
        val expected = repo.config.value.admin.pinHash.lowercase()
        val actual = sha256Hex(entered)

        if (expected.isBlank()) {
            // Erstinbetriebnahme - kein Hash gesetzt, nur Bypass-Code "0000"
            if (entered == "0000") {
                showAdminPanel()
            } else {
                Toast.makeText(
                    this,
                    com.kiosk.mda.R.string.admin_no_pin_configured,
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        if (actual == expected) {
            showAdminPanel()
        } else {
            Toast.makeText(this, com.kiosk.mda.R.string.admin_wrong_pin, Toast.LENGTH_SHORT).show()
            binding.txtPin.text?.clear()
        }
    }

    private fun showAdminPanel() {
        pinVerified = true
        binding.pinSection.visibility = android.view.View.GONE
        binding.adminSection.visibility = android.view.View.VISIBLE
    }

    private fun triggerSync() {
        if (!pinVerified) return
        binding.btnSync.isEnabled = false
        lifecycleScope.launch {
            val result = repo.fetchRemote()
            val msg = when (result) {
                is ConfigRepository.SyncResult.Updated -> "Config aktualisiert"
                ConfigRepository.SyncResult.NotModified -> "Keine Änderungen"
                ConfigRepository.SyncResult.NoUrl -> "Keine Config-URL"
                is ConfigRepository.SyncResult.HttpError -> "HTTP ${result.code}"
                is ConfigRepository.SyncResult.NetworkError -> "Netz-Fehler: ${result.msg}"
                is ConfigRepository.SyncResult.ParseError -> "XML-Fehler: ${result.msg}"
            }
            Toast.makeText(this@AdminActivity, msg, Toast.LENGTH_LONG).show()
            binding.btnSync.isEnabled = true
        }
    }

    private fun saveOverrideUrl() {
        if (!pinVerified) return
        val url = binding.txtConfigUrl.text.toString().trim()
        repo.setOverrideUrl(url.ifBlank { null })
        Toast.makeText(this, "URL gespeichert: ${url.ifBlank { "(none)" }}", Toast.LENGTH_SHORT).show()
    }

    private fun setEnvironment(env: String) {
        if (!pinVerified) return
        repo.setEnvironment(env)
        binding.txtCurrentEnv.text = getString(com.kiosk.mda.R.string.admin_env_label, env)
        Toast.makeText(this, "Environment: $env", Toast.LENGTH_SHORT).show()
    }

    private fun exitKiosk() {
        if (!pinVerified) return
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("about:blank")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        finish()
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
