package com.kiosk.mda

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * Plain-WebView-Subclass.
 *
 * oskEnabled wird nur als UI-Flag verwendet (für Icon-State des Toggle-Buttons).
 * Alle OSK-Logik läuft über das System-Setting Settings.Secure.show_ime_with_hard_keyboard
 * und InputMethodManager.toggleSoftInput in MainActivity - keine View-Overrides hier
 * weil sie die WebView-Eigenlogik (InputConnection mit HTML-Inputs) brechen.
 */
class KioskWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var oskEnabled: Boolean = false
}
