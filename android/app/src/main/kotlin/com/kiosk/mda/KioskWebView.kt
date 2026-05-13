package com.kiosk.mda

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * WebView mit oskEnabled-Flag.
 *
 * Tatsächliches Blocken der Bildschirm-Tastatur passiert in MainActivity über
 * einen WindowInsets-Listener (siehe MainActivity.setupImeBlocker). Grund:
 * Die Chromium-WebView ruft InputMethodManager.showSoftInput() intern direkt
 * über ihren ImeAdapter auf - View-Override-Hooks (onCheckIsTextEditor,
 * onCreateInputConnection) werden dabei umgangen.
 *
 * Der WindowInsets-Listener fängt das IME beim Aufpoppen und blendet es wieder
 * aus wenn oskEnabled=false. Robust gegen alle WebView-Implementierungen.
 */
class KioskWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var oskEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) hideSoftKeyboard()
        }

    private fun hideSoftKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (_: Exception) {
        }
    }
}
