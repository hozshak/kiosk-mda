package com.kiosk.mda

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * WebView mit On-Screen-Keyboard-Kontrolle.
 *
 * Modi:
 *   OFF  – OSK wird komplett unterdrückt (HID-Scanner-Eingaben funktionieren weiter).
 *   AUTO – OSK nur wenn ein Password-Feld fokussiert ist (Detection via JS-Bridge).
 *   ON   – OSK normal (wie Standard-WebView).
 *
 * Implementierung: onCreateInputConnection setzt inputType auf TYPE_NULL wenn OSK
 * unterdrückt werden soll. Das blockt die Soft-Keyboard-Anzeige auf System-Ebene,
 * lässt aber HID-Tastatur-Events (Barcode-Scanner) durch.
 *
 * Nach jeder Modus-Änderung wird InputMethodManager.restartInput() aufgerufen,
 * damit Android onCreateInputConnection neu ausführt.
 */
class KioskWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    enum class OskMode {
        OFF, AUTO, ON;

        fun next(): OskMode = when (this) {
            OFF -> AUTO
            AUTO -> ON
            ON -> OFF
        }

        companion object {
            fun fromString(s: String?): OskMode = when (s?.lowercase()) {
                "on", "true", "1" -> ON
                "auto" -> AUTO
                else -> OFF
            }
        }
    }

    var oskMode: OskMode = OskMode.OFF
        set(value) {
            field = value
            restartImeInput()
        }

    /** Wird von OskBridge.onFieldFocus aus JS gesetzt. */
    var passwordFieldFocused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (oskMode == OskMode.AUTO) restartImeInput()
        }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        val allow = when (oskMode) {
            OskMode.ON -> true
            OskMode.OFF -> false
            OskMode.AUTO -> passwordFieldFocused
        }
        if (!allow) {
            outAttrs.inputType = InputType.TYPE_NULL
        }
        return ic
    }

    private fun restartImeInput() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.restartInput(this)
            // Im OFF-Modus zusätzlich aktiv ausblenden falls gerade eingeblendet
            if (oskMode == OskMode.OFF || (oskMode == OskMode.AUTO && !passwordFieldFocused)) {
                imm.hideSoftInputFromWindow(windowToken, 0)
            }
        } catch (_: Exception) {
        }
    }
}
