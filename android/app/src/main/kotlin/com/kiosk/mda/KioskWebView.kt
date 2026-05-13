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
 * oskEnabled = false (default): Die System-Soft-Keyboard erscheint NICHT wenn Felder
 *   in der Webseite fokussiert werden. HID-Tastatur-Eingaben (Barcode-Scanner) bleiben
 *   funktional weil sie als KeyEvent kommen, nicht über InputConnection.
 *
 * oskEnabled = true: normales WebView-Verhalten, OSK öffnet sich.
 *
 * Implementierung:
 *  - onCheckIsTextEditor() entscheidet ob System uns überhaupt InputConnection anfragt
 *  - onCreateInputConnection als zusätzlicher Block (setzt TYPE_NULL falls doch aufgerufen)
 *  - imm.restartInput() bei Modus-Wechsel zwingt Re-Evaluation
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
            restartImeInput()
        }

    override fun onCheckIsTextEditor(): Boolean {
        // Wenn false, fragt das System gar nicht erst nach InputConnection
        // und das Soft-Keyboard erscheint nie.
        return oskEnabled
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        if (!oskEnabled) {
            // Zweite Verteidigungslinie falls das System trotz onCheckIsTextEditor=false
            // doch fragt: TYPE_NULL = keine Soft-Tastatur.
            outAttrs.inputType = InputType.TYPE_NULL
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        }
        return ic
    }

    private fun restartImeInput() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (oskEnabled) {
                imm.restartInput(this)
            } else {
                imm.hideSoftInputFromWindow(windowToken, 0)
                imm.restartInput(this)
            }
        } catch (_: Exception) {
        }
    }
}
