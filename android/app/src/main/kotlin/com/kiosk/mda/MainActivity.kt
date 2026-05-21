package com.kiosk.mda

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.PermissionRequest
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.provider.Settings
import android.util.Log
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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
import com.kiosk.mda.update.UpdateChecker
import com.kiosk.mda.update.UpdateInstaller
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: ConfigRepository
    private lateinit var pushClient: PushClient
    private lateinit var updateChecker: UpdateChecker
    private var pendingUpdate: UpdateChecker.UpdateInfo? = null

    private var triplePressCount = 0
    private var lastTriplePressMs = 0L

    private var pendingWebPermission: PermissionRequest? = null
    private val androidPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val req = pendingWebPermission ?: return@registerForActivityResult
        pendingWebPermission = null
        val grantedResources = mutableListOf<String>()
        for (resource in req.resources) {
            val androidPerm = when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> null
            }
            if (androidPerm != null && granted[androidPerm] == true) {
                grantedResources.add(resource)
            }
        }
        if (grantedResources.isNotEmpty()) {
            req.grant(grantedResources.toTypedArray())
        } else {
            req.deny()
        }
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applyConfig(repo.config.value)
        }
    }

    private val apkUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lifecycleScope.launch { checkForUpdate() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository.get(this)
        pushClient = PushClient(applicationContext)
        updateChecker = UpdateChecker(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // KEIN setDecorFitsSystemWindows(false) - das brach WebView-Input
        // auf manchen PDAs (Edge-to-Edge stört IME-Routing).
        enableImmersive()
        setupOskSuppression()
        enableLockTaskIfDeviceOwner()

        setupWebView()
        setupAdminTrigger()
        setupOskToggle()
        setupUpdateBanner()
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
        ContextCompat.registerReceiver(
            this,
            apkUpdateReceiver,
            IntentFilter("com.kiosk.mda.APK_UPDATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        lifecycleScope.launch {
            repo.fetchRemote()
        }

        // WebSocket-Push starten - Backend pingt bei Config-Update
        pushClient.start(lifecycleScope)

        // APK-Update beim Start prüfen
        lifecycleScope.launch {
            checkForUpdate()
        }
    }

    private suspend fun checkForUpdate() {
        val info = updateChecker.check() ?: return
        runOnUiThread { showUpdateBanner(info) }
    }

    fun onPushApkUpdate() {
        // Wird vom PushClient aufgerufen wenn Server "apk-update" sendet
        lifecycleScope.launch { checkForUpdate() }
    }

    private fun showUpdateBanner(info: UpdateChecker.UpdateInfo) {
        pendingUpdate = info
        binding.updateBanner.visibility = View.VISIBLE
        binding.updateBannerText.text = getString(
            R.string.update_available, info.versionName, info.versionCode
        )
    }

    private fun triggerInstall() {
        val info = pendingUpdate ?: return
        if (!UpdateInstaller.canInstallPackages(this)) {
            Toast.makeText(this, R.string.update_install_permission_required, Toast.LENGTH_LONG).show()
            UpdateInstaller.openInstallPermissionSettings(this)
            return
        }
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = updateChecker.download(info)
            if (file == null) {
                Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            UpdateInstaller.install(this@MainActivity, file)
        }
    }

    override fun onPause() {
        super.onPause()
        if (repo.config.value.browser.clearCacheOnExit) {
            clearWebViewData()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configReceiver) }
        runCatching { unregisterReceiver(apkUpdateReceiver) }
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
        // Wir rufen enableImmersive NICHT erneut bei jedem focus-change auf,
        // weil das beim IME-Öffnen Focus-Stealing verursachen kann.
        // STICKY-Mode in WindowInsetsControllerCompat regelt das automatisch.
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

        // OSK aus Config anwenden
        binding.webView.oskEnabled = config.browser.oskEnabled
        binding.oskToggle.visibility = if (config.browser.oskToggleVisible) View.VISIBLE else View.GONE
        updateOskToggleIcon()
        updateOskJsFlag()

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
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // HTTP-Ressourcen in HTTPS-Seiten erlauben (interner Kiosk)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    injectOskFocusFix(view)
                }

                override fun onReceivedSslError(
                    view: WebView, handler: SslErrorHandler, error: SslError
                ) {
                    // Self-signed / internal Certs akzeptieren (Kiosk in geschütztem Netz)
                    Log.w("Kiosk", "SSL error for ${error.url}: code=${error.primaryError}, proceeding")
                    handler.proceed()
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (!request.isForMainFrame) return
                    Log.e("Kiosk", "Load error ${error.errorCode} for ${request.url}: ${error.description}")
                    showErrorPage(view, request.url.toString(), error.description.toString(), error.errorCode)
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
                ) {
                    if (!request.isForMainFrame) return
                    Log.e("Kiosk", "HTTP error ${errorResponse.statusCode} for ${request.url}")
                    showErrorPage(
                        view,
                        request.url.toString(),
                        errorResponse.reasonPhrase ?: "HTTP error",
                        errorResponse.statusCode
                    )
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { handleWebPermissionRequest(request) }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    if (pendingWebPermission === request) pendingWebPermission = null
                }

                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                    Log.d(
                        "KioskJS",
                        "[${msg.messageLevel()}] ${msg.message()} @ ${msg.sourceId()}:${msg.lineNumber()}"
                    )
                    return true
                }
            }
            addJavascriptInterface(OskBridge(), "KioskOsk")
        }
        CookieManager.getInstance().setAcceptCookie(true)
    }

    /**
     * Web-Seite fragt via getUserMedia() o.ä. nach Kamera/Mikrofon.
     * Mappe Web-Resources auf Android-Runtime-Permissions und frage falls noch nicht erteilt.
     */
    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val needed = mutableListOf<String>()
        val granted = mutableListOf<String>()
        for (resource in request.resources) {
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    if (hasPermission(Manifest.permission.CAMERA)) {
                        granted.add(resource)
                    } else {
                        needed.add(Manifest.permission.CAMERA)
                    }
                }
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        granted.add(resource)
                    } else {
                        needed.add(Manifest.permission.RECORD_AUDIO)
                    }
                }
                PermissionRequest.RESOURCE_MIDI_SYSEX,
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> {
                    granted.add(resource)
                }
            }
        }
        if (needed.isEmpty()) {
            request.grant(granted.toTypedArray())
        } else {
            pendingWebPermission = request
            androidPermLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun showErrorPage(view: WebView, url: String, message: String, code: Int) {
        val escapedUrl = url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val escapedMsg = message.replace("<", "&lt;").replace(">", "&gt;")
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: system-ui, sans-serif; background: #1a1f29; color: #e6e8eb;
                           padding: 32px; line-height: 1.6; }
                    .box { max-width: 600px; margin: 0 auto; background: #252b38; padding: 24px;
                           border-radius: 12px; border-left: 4px solid #e54b4b; }
                    h1 { font-size: 20px; margin-bottom: 8px; color: #ff6b6b; }
                    .code { font-family: monospace; background: #0f1419; padding: 2px 8px;
                            border-radius: 4px; color: #4da3ff; }
                    .url { word-break: break-all; opacity: 0.8; font-size: 13px; margin-top: 12px; }
                    button { background: #4da3ff; color: white; border: none; padding: 10px 18px;
                             border-radius: 6px; margin-top: 16px; cursor: pointer; font-size: 14px; }
                </style>
            </head>
            <body>
                <div class="box">
                    <h1>Seite konnte nicht geladen werden</h1>
                    <p><span class="code">$code</span> $escapedMsg</p>
                    <div class="url">$escapedUrl</div>
                    <button onclick="location.reload()">Erneut versuchen</button>
                </div>
            </body>
            </html>
        """.trimIndent()
        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun setupUpdateBanner() {
        binding.updateBannerInstall.setOnClickListener { triggerInstall() }
        binding.updateBannerDismiss.setOnClickListener {
            binding.updateBanner.visibility = View.GONE
        }
    }

    private fun setupOskToggle() {
        binding.oskToggle.setOnClickListener {
            val newState = !binding.webView.oskEnabled
            binding.webView.oskEnabled = newState
            updateOskToggleIcon()
            updateOskJsFlag()

            // System-Setting setzen (klappt nur mit WRITE_SECURE_SETTINGS, sonst no-op)
            setImeWithHardKeyboard(newState)

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager

            if (newState) {
                // Force-Show als Fallback wenn show_ime_with_hard_keyboard nicht gegranted
                // werden konnte. toggleSoftInput(SHOW_FORCED) zeigt die IME ohne View-Override
                // - User tippt dann auf HTML-Feld, WebView's eigene InputConnection routet
                // Tipps korrekt ins Feld.
                @Suppress("DEPRECATION")
                imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
            } else {
                imm.hideSoftInputFromWindow(binding.webView.windowToken, 0)
                @Suppress("DEPRECATION")
                imm.toggleSoftInput(0, android.view.inputmethod.InputMethodManager.HIDE_IMPLICIT_ONLY)
            }

            val label = if (newState) {
                getString(R.string.osk_mode_on) + " - tippe ins Eingabefeld"
            } else {
                getString(R.string.osk_mode_off)
            }
            Toast.makeText(this, label, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Setzt Settings.Secure.show_ime_with_hard_keyboard.
     * Benötigt WRITE_SECURE_SETTINGS (signature permission).
     * One-time-grant per adb:
     *   adb shell pm grant com.kiosk.mda android.permission.WRITE_SECURE_SETTINGS
     */
    @SuppressLint("WrongConstant")
    private fun setImeWithHardKeyboard(enabled: Boolean) {
        try {
            android.provider.Settings.Secure.putInt(
                contentResolver, "show_ime_with_hard_keyboard", if (enabled) 1 else 0
            )
        } catch (_: SecurityException) {
            // Permission fehlt - User muss adb-grant ausführen oder die Einstellung manuell
            // in Android-Einstellungen -> Sprachen -> Physische Tastatur aktivieren.
        } catch (_: Exception) {
        }
    }

    private fun updateOskToggleIcon() {
        val on = binding.webView.oskEnabled
        binding.oskToggle.setImageResource(
            if (on) R.drawable.ic_keyboard else R.drawable.ic_keyboard_off
        )
        binding.oskToggle.alpha = if (on) 1.0f else 0.6f
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

    /**
     * Wenn OSK aus: jedes Mal wenn die System-IME hochpoppt (z.B. weil WebView den
     * Focus auf ein Eingabefeld setzt) sofort wieder ausblenden.
     * Minimale Logik - kein Padding, kein Resize, nur Hide.
     */
    private fun setupOskSuppression() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible && !binding.webView.oskEnabled) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                binding.webView.post {
                    imm.hideSoftInputFromWindow(binding.webView.windowToken, 0)
                }
            }
            insets
        }
    }

    /**
     * JS->Native-Brücke. Wird vom injizierten focusin-Fix aufgerufen, nachdem das
     * Eingabefeld im DOM blur+refokussiert wurde: die WebView hat dann eine frische
     * InputConnection, aber das blur() hat die Tastatur ausgeblendet. Wir bauen die
     * IME-Verbindung per restartInput neu auf und blenden die Tastatur wieder ein -
     * nativ (showSoftInput), weil ein programmatisches el.focus() ohne Geste die
     * Tastatur nicht zuverlässig zurückholt.
     */
    private inner class OskBridge {
        @android.webkit.JavascriptInterface
        fun showKeyboard() {
            binding.webView.post {
                if (!binding.webView.oskEnabled) return@post
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                runCatching {
                    imm.restartInput(binding.webView)
                    imm.showSoftInput(binding.webView, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    /**
     * Eigentlicher Fix (DOM-Ebene): repliziert den manuellen tools<->blending-Wechsel.
     *
     * Per Diagnose ist klar: nativer restartInput allein behebt die tote IME-Verbindung
     * NICHT - erst wenn das HTML-Feld im DOM den Fokus verliert und neu bekommt (wie beim
     * Tab-Wechsel) baut die WebView eine frische InputConnection auf. Wir injizieren daher
     * einen focusin-Listener, der bei aktiver Tastatur jedes fokussierte Eingabefeld einmal
     * blur+refokussiert. Läuft auf jeder Seite (auch fremdes ERP), da via evaluateJavascript
     * injiziert - umgeht die Page-CSP.
     */
    private fun injectOskFocusFix(view: WebView) {
        val oskOn = binding.webView.oskEnabled
        val js = """
            (function(){
              window.__kioskOskOn = $oskOn;
              if (window.__kioskOskFixInstalled) return;
              window.__kioskOskFixInstalled = true;
              var busy = false;
              document.addEventListener('focusin', function(e){
                if (!window.__kioskOskOn || busy) return;
                var el = e.target;
                if (!el || !(el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) return;
                busy = true;
                // blur + refocus -> WebView baut die InputConnection fuer das Feld neu auf,
                // danach Tastatur nativ wieder einblenden (blur hat sie ausgeblendet).
                setTimeout(function(){
                  try { el.blur(); } catch (_) {}
                  setTimeout(function(){
                    try { el.focus({ preventScroll: true }); } catch (_) { try { el.focus(); } catch (_) {} }
                    try { if (window.KioskOsk && KioskOsk.showKeyboard) KioskOsk.showKeyboard(); } catch (_) {}
                    setTimeout(function(){ busy = false; }, 0);
                  }, 60);
                }, 0);
              }, true);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /** OSK-Zustand ins JS spiegeln, damit der focusin-Fix nur bei aktiver Tastatur greift. */
    private fun updateOskJsFlag() {
        val oskOn = binding.webView.oskEnabled
        binding.webView.evaluateJavascript("window.__kioskOskOn = $oskOn;", null)
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
        // Diagnose-Log: alles was an dispatch ankommt - via `adb logcat -s KioskKey` lesbar
        Log.d(
            "KioskKey",
            "action=${event.action} code=${event.keyCode} uni=${event.unicodeChar} " +
                "src=${event.source} dev=${event.deviceId} flags=${event.flags}"
        )
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> super.dispatchKeyEvent(event)
        }
    }
}
