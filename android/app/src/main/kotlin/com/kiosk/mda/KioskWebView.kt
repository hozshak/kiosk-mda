package com.kiosk.mda

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * WebView mit oskEnabled-Flag.
 *
 * onCheckIsTextEditor wird überschrieben um beim Toggle "ich bin ein Text-Editor"
 * zu signalisieren - sonst zeigt das System keine IME wenn keine input-Element im
 * Web-Content fokussiert ist.
 *
 * Tatsächliches Verhalten beim Auto-Show wird in MainActivity via WindowInsets-
 * Listener gesteuert.
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

    override fun onCheckIsTextEditor(): Boolean {
        // Wenn OSK an: behaupte "Text-Editor" damit das System bereit ist die IME zu zeigen.
        // Wenn aus: Default-Verhalten des WebView (true nur bei fokussiertem input).
        return oskEnabled || super.onCheckIsTextEditor()
    }

    private fun hideSoftKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (_: Exception) {
        }
    }
}
