package com.kiosk.mda

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.provider.Settings
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kiosk.mda.admin.AdminActivity
import com.kiosk.mda.admin.KioskDeviceAdminReceiver
import com.kiosk.mda.config.ConfigRepository
import com.kiosk.mda.config.ConfigSyncWorker
import com.kiosk.mda.config.KioskConfig
import com.kiosk.mda.config.Orientation
import com.kiosk.mda.databinding.ActivityMainBinding
import com.kiosk.mda.push.PushClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: ConfigRepository
    private lateinit var pushClient: PushClient
    private lateinit var oskBridge: OskBridge

    private var triplePressCount = 0
    private var lastTriplePressMs = 0L

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applyConfig(repo.config.value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository.get(this)
        pushClient = PushClient(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableImmersive()
        enableLockTaskIfDeviceOwner()

        setupWebView()
        setupAdminTrigger()
        setupOskToggle()
        observeConfig()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack()
            }
        })

        ContextCompat.registerReceiver(
            this,
            configReceiver,
            IntentFilter(ConfigSyncWorker.ACTION_CONFIG_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        lifecycleScope.launch {
            repo.fetchRemote()
        }

        // WebSocket-Push starten - Backend pingt bei Config-Update
        pushClient.start(lifecycleScope)
    }

    override fun onPause() {
        super.onPause()
        if (repo.config.value.browser.clearCacheOnExit) {
            clearWebViewData()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configReceiver) }
        runCatching { pushClient.stop() }
        if (repo.config.value.browser.clearCacheOnExit) {
            clearWebViewData()
        }
        super.onDestroy()
    }

    /**
     * Setzt den Display-Timeout.
     * - 0 oder negativ: FLAG_KEEP_SCREEN_ON (always-on, immer wenn App im Vordergrund)
     * - > 0: Versucht system-weit SCREEN_OFF_TIMEOUT in Sekunden zu setzen
     *   (braucht WRITE_SETTINGS - manuell zu erteilen oder via Device-Owner)
     */
    private fun applyDisplayTimeout(seconds: Int) {
        if (seconds <= 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            val canWrite = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.System.canWrite(this)
            } else true

            if (canWrite) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    seconds * 1000
                )
                Log.i("Kiosk", "Screen-off timeout set to ${seconds}s")
            } else {
                Log.w("Kiosk", "WRITE_SETTINGS not granted - cannot set screen-off timeout")
            }
        } catch (e: Exception) {
            Log.w("Kiosk", "applyDisplayTimeout failed: ${e.message}")
        }
    }

    private fun clearWebViewData() {
        try {
            binding.webView.clearCache(true)
            binding.webView.clearHistory()
            binding.webView.clearFormData()
            binding.webView.clearMatches()
            binding.webView.clearSslPreferences()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            Log.i("Kiosk", "WebView data cleared (cache, cookies, storage)")
        } catch (e: Exception) {
            Log.w("Kiosk", "clearWebViewData failed: ${e.message}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    private fun observeConfig() {
        lifecycleScope.launch {
            repo.config.collectLatest { applyConfig(it) }
        }
    }

    private fun applyConfig(config: KioskConfig) {
        requestedOrientation = when (config.device.orientation) {
            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        applyDisplayTimeout(config.device.displayTimeoutSec)

        binding.webView.settings.javaScriptEnabled = config.browser.javaScriptEnabled

        // OSK-Modus anwenden
        binding.webView.oskMode = KioskWebView.OskMode.fromString(config.browser.oskMode)
        binding.oskToggle.visibility = if (config.browser.oskToggleVisible) View.VISIBLE else View.GONE
        updateOskToggleIcon()

        val current = binding.webView.url
        val target = config.browser.startUrl.takeIf {
            it.isNotBlank() && it != "about:blank"
        }

        when {
            target == null && !current.isNullOrBlank() && current != "about:blank" -> {
                // Config wurde geleert - WebView leeren
                binding.webView.loadUrl("about:blank")
            }
            target != null && (current.isNullOrBlank() || current == "about:blank") -> {
                // Erst-Aufruf - URL laden
                binding.webView.loadUrl(target)
            }
            target != null && current != target && current?.startsWith("file:///") == true -> {
                // Wechsel von alter Asset-URL zur konfigurierten
                binding.webView.loadUrl(target)
            }
        }

        renderBookmarks(config)
    }

    private fun renderBookmarks(config: KioskConfig) {
        binding.bookmarkBar.removeAllViews()
        if (config.browser.bookmarks.isEmpty()) {
            binding.bookmarkBarScroll.visibility = View.GONE
            binding.bookmarkBar.visibility = View.GONE
            return
        }
        binding.bookmarkBarScroll.visibility = View.VISIBLE
        binding.bookmarkBar.visibility = View.VISIBLE
        config.browser.bookmarks.forEach { bookmark ->
            val btn = com.google.android.material.button.MaterialButton(this).apply {
                text = bookmark.name
                setOnClickListener { binding.webView.loadUrl(bookmark.url) }
            }
            binding.bookmarkBar.addView(btn)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        oskBridge = OskBridge(this, binding.webView)
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            addJavascriptInterface(oskBridge, OskBridge.INTERFACE_NAME)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // Bei jedem Seiten-Ende den OSK-Detector neu injizieren
                    view.evaluateJavascript(OskBridge.INJECTED_JS, null)
                }
            }
            webChromeClient = WebChromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
    }

    private fun setupOskToggle() {
        binding.oskToggle.setOnClickListener {
            val next = binding.webView.oskMode.next()
            binding.webView.oskMode = next
            updateOskToggleIcon()
            val label = when (next) {
                KioskWebView.OskMode.OFF -> getString(R.string.osk_mode_off)
                KioskWebView.OskMode.AUTO -> getString(R.string.osk_mode_auto)
                KioskWebView.OskMode.ON -> getString(R.string.osk_mode_on)
            }
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOskToggleIcon() {
        val res = when (binding.webView.oskMode) {
            KioskWebView.OskMode.OFF -> R.drawable.ic_keyboard_off
            KioskWebView.OskMode.AUTO -> R.drawable.ic_keyboard_off
            KioskWebView.OskMode.ON -> R.drawable.ic_keyboard
        }
        binding.oskToggle.setImageResource(res)
        binding.oskToggle.alpha = if (binding.webView.oskMode == KioskWebView.OskMode.OFF) 0.6f else 1.0f
    }

    private fun setupAdminTrigger() {
        binding.adminTrigger.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTriplePressMs > 2000) triplePressCount = 0
            triplePressCount++
            lastTriplePressMs = now
            if (triplePressCount >= 3) {
                triplePressCount = 0
                openAdmin()
            }
        }
        // Fallback: Long-Press (1,5s) öffnet Admin direkt
        binding.adminTrigger.setOnLongClickListener {
            openAdmin()
            true
        }
    }

    private fun openAdmin() {
        startActivity(Intent(this, com.kiosk.mda.admin.AdminActivity::class.java))
    }

    private fun enableImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun enableLockTaskIfDeviceOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Nur Power-Menü erlauben, damit Geräte sicher heruntergefahren werden können.
                    dpm.setLockTaskFeatures(
                        admin,
                        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                    )
                }
                startLockTask()
            } catch (_: SecurityException) {
                // Kein Device-Owner oder Berechtigung fehlt - Fallback auf reinen Launcher-Mode
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> super.dispatchKeyEvent(event)
        }
    }
}
