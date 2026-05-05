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
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: ConfigRepository

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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableImmersive()
        enableLockTaskIfDeviceOwner()

        setupWebView()
        setupAdminTrigger()
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
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configReceiver) }
        super.onDestroy()
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

        if (config.device.displayTimeoutSec == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        binding.webView.settings.javaScriptEnabled = config.browser.javaScriptEnabled

        val current = binding.webView.url
        if (current.isNullOrBlank() || current == "about:blank") {
            val target = config.browser.startUrl.ifBlank { "about:blank" }
            binding.webView.loadUrl(target)
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
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
    }

    private fun setupAdminTrigger() {
        binding.adminTrigger.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTriplePressMs > 1500) triplePressCount = 0
            triplePressCount++
            lastTriplePressMs = now
            if (triplePressCount >= 3) {
                triplePressCount = 0
                startActivity(Intent(this, AdminActivity::class.java))
            }
        }
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
