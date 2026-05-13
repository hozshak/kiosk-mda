package com.kiosk.mda.admin

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kiosk.mda.BuildConfig
import com.kiosk.mda.config.ConfigRepository
import com.kiosk.mda.databinding.ActivityAdminBinding
import com.kiosk.mda.update.UpdateChecker
import com.kiosk.mda.update.UpdateInstaller
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
        binding.txtPin.setOnEditorActionListener { _, _, _ -> verifyPin(); true }

        binding.btnSync.setOnClickListener { triggerSync() }
        binding.btnExit.setOnClickListener { exitKiosk() }
        binding.btnSetUrl.setOnClickListener { saveOverrideUrl() }
        binding.btnEnvProd.setOnClickListener { setEnvironment("prod") }
        binding.btnEnvTest.setOnClickListener { setEnvironment("test") }

        // Test-URL: getrennte Save / Load+Save / Clear Buttons
        binding.btnLoadTestUrl.setOnClickListener { loadAndSaveTestUrl() }
        binding.btnSaveTestUrl.setOnClickListener { saveTestUrl() }
        binding.btnClearTestUrl.setOnClickListener { clearTestUrl() }

        // Launcher / Home-App
        binding.btnOpenHomeSettings.setOnClickListener { openHomeSettings() }
        binding.btnResetDefaultLauncher.setOnClickListener { openCurrentLauncherDetails() }
        binding.btnPickLauncherNow.setOnClickListener { triggerLauncherPicker() }

        // Berechtigungen
        binding.btnGrantWriteSettings.setOnClickListener { requestWriteSettings() }

        // Update
        binding.txtCurrentVersion.text = getString(
            com.kiosk.mda.R.string.admin_current_version,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        binding.btnInputDiag.setOnClickListener { loadInputDiag() }

        binding.chipExample1.setOnClickListener {
            binding.txtTestUrl.setText("https://duckduckgo.com")
        }
        binding.chipExample2.setOnClickListener {
            binding.txtTestUrl.setText("https://de.m.wikipedia.org/wiki/Hauptseite")
        }
        binding.chipExample3.setOnClickListener {
            binding.txtTestUrl.setText("https://www.tagesschau.de")
        }

        binding.txtConfigUrl.setText(repo.overrideUrl() ?: "")
        binding.txtTestUrl.setText(repo.testStartUrl() ?: "")
        binding.txtCurrentEnv.text = getString(
            com.kiosk.mda.R.string.admin_env_label,
            repo.environment()
        )
    }

    override fun onResume() {
        super.onResume()
        if (pinVerified) {
            updateCurrentLauncherDisplay()
            updateWriteSettingsStatus()
        }
    }

    private fun updateWriteSettingsStatus() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else true
        val statusText = if (granted) {
            getString(com.kiosk.mda.R.string.admin_permission_granted)
        } else {
            getString(com.kiosk.mda.R.string.admin_permission_missing)
        }
        binding.txtWriteSettingsStatus.text =
            "${getString(com.kiosk.mda.R.string.admin_permission_write_settings)}: $statusText"
        binding.btnGrantWriteSettings.isEnabled = !granted
    }

    private fun loadInputDiag() {
        if (!pinVerified) return
        // Server-URL ableiten aus Config (z.B. http://192.168.115.177:8989/config/prod -> /diag.html)
        val configUrl = repo.effectiveConfigUrl()
        val idx = configUrl.indexOf("/config/")
        val diagUrl = if (idx > 0) configUrl.substring(0, idx) + "/diag.html"
        else "file:///android_asset/diag/input-test.html"

        repo.setTestStartUrl(diagUrl)
        Toast.makeText(this, "Lade Diagnose-Seite: $diagUrl", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun checkForUpdate() {
        if (!pinVerified) return
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch {
            val checker = UpdateChecker(applicationContext)
            val info = checker.check()
            binding.btnCheckUpdate.isEnabled = true
            if (info == null) {
                Toast.makeText(this@AdminActivity,
                    com.kiosk.mda.R.string.admin_update_none,
                    Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(this@AdminActivity,
                "Version ${info.versionName} verfügbar, lade…",
                Toast.LENGTH_SHORT).show()
            val file = checker.download(info)
            if (file == null) {
                Toast.makeText(this@AdminActivity,
                    com.kiosk.mda.R.string.update_download_failed,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!UpdateInstaller.canInstallPackages(this@AdminActivity)) {
                Toast.makeText(this@AdminActivity,
                    com.kiosk.mda.R.string.update_install_permission_required,
                    Toast.LENGTH_LONG).show()
                UpdateInstaller.openInstallPermissionSettings(this@AdminActivity)
                return@launch
            }
            UpdateInstaller.install(this@AdminActivity, file)
        }
    }

    private fun requestWriteSettings() {
        if (!pinVerified) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Auf dieser Android-Version automatisch erteilt", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Konnte Settings nicht öffnen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun verifyPin() {
        val entered = binding.txtPin.text.toString()
        val expected = repo.config.value.admin.pinHash.lowercase()
        val actual = sha256Hex(entered)

        if (expected.isBlank()) {
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
        updateCurrentLauncherDisplay()
        updateWriteSettingsStatus()
    }

    // ============ Test-URL ============

    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    private fun saveTestUrl() {
        if (!pinVerified) return
        val url = normalizeUrl(binding.txtTestUrl.text.toString())
        if (url == null) {
            Toast.makeText(this, "Keine URL eingegeben", Toast.LENGTH_SHORT).show()
            return
        }
        repo.setTestStartUrl(url)
        binding.txtTestUrl.setText(url)
        Toast.makeText(this, "Gespeichert: $url", Toast.LENGTH_SHORT).show()
    }

    private fun loadAndSaveTestUrl() {
        if (!pinVerified) return
        val url = normalizeUrl(binding.txtTestUrl.text.toString())
        if (url == null) {
            Toast.makeText(this, "Keine URL eingegeben", Toast.LENGTH_SHORT).show()
            return
        }
        repo.setTestStartUrl(url)
        Toast.makeText(this, "Lade $url", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun clearTestUrl() {
        if (!pinVerified) return
        repo.setTestStartUrl(null)
        binding.txtTestUrl.text?.clear()
        Toast.makeText(this, "Test-URL zurückgesetzt", Toast.LENGTH_SHORT).show()
    }

    // ============ Launcher / Home-App ============

    private fun currentDefaultLauncher(): Pair<String, String>? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        val pkg = resolved.activityInfo?.packageName ?: return null
        val label = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) {
            pkg
        }
        return pkg to label
    }

    private fun updateCurrentLauncherDisplay() {
        val (pkg, label) = currentDefaultLauncher() ?: run {
            binding.txtCurrentLauncher.text = "Kein Launcher gesetzt"
            return
        }
        val display = if (pkg == packageName) "$label (das ist diese App)" else "$label  ($pkg)"
        binding.txtCurrentLauncher.text = getString(
            com.kiosk.mda.R.string.admin_launcher_current, display
        )
    }

    private fun openHomeSettings() {
        if (!pinVerified) return
        val intents = listOf(
            Intent("android.settings.HOME_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
                // try next
            }
        }
        Toast.makeText(this, "Keine passende Einstellung gefunden", Toast.LENGTH_SHORT).show()
    }

    private fun openCurrentLauncherDetails() {
        if (!pinVerified) return
        val (pkg, label) = currentDefaultLauncher() ?: run {
            Toast.makeText(this, "Kein Default-Launcher gesetzt", Toast.LENGTH_SHORT).show()
            return
        }
        if (pkg == packageName) {
            Toast.makeText(this, "Wir sind bereits Default-Launcher", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "App-Info zu $label öffnet. Dort: 'Standardmäßig öffnen' → 'Standardeinstellungen löschen'",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Konnte App-Info nicht öffnen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerLauncherPicker() {
        if (!pinVerified) return
        // Versuch 1: HOME_SETTINGS
        try {
            startActivity(Intent("android.settings.HOME_SETTINGS"))
            return
        } catch (_: ActivityNotFoundException) {
        }
        // Versuch 2: Chooser-Intent ueber HOME-Intent
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val chooser = Intent.createChooser(home, "Launcher wählen").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "Launcher-Auswahl nicht möglich: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ============ Server-Config ============

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
