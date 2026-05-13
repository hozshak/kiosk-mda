package com.kiosk.mda

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface

/**
 * JS-Bridge: WebContent meldet ob ein Password-Feld fokussiert ist.
 * Wird auf jeder geladenen Seite per evaluateJavascript ein kleiner Listener injiziert.
 */
class OskBridge(
    private val activity: Activity,
    private val webView: KioskWebView
) {
    @JavascriptInterface
    fun onFieldFocus(isPassword: Boolean) {
        activity.runOnUiThread {
            webView.passwordFieldFocused = isPassword
            if (webView.oskMode == KioskWebView.OskMode.AUTO) {
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                if (isPassword) {
                    webView.requestFocus()
                    imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
                } else {
                    imm.hideSoftInputFromWindow(webView.windowToken, 0)
                }
            }
        }
    }

    companion object {
        const val INTERFACE_NAME = "KioskOsk"

        /**
         * Wird einmal pro Seitenladen via evaluateJavascript injiziert.
         * - listent global auf focusin/focusout
         * - meldet password-Felder ODER Felder mit data-osk="enabled"
         */
        val INJECTED_JS = """
            (function() {
                if (window.__kioskOskInstalled) return;
                window.__kioskOskInstalled = true;

                function isOskField(el) {
                    if (!el) return false;
                    if (el.tagName === 'INPUT' && el.type === 'password') return true;
                    if (el.dataset && el.dataset.osk === 'enabled') return true;
                    return false;
                }

                function report(focused) {
                    try {
                        window.KioskOsk && window.KioskOsk.onFieldFocus(focused);
                    } catch (e) {}
                }

                document.addEventListener('focusin', function(e) {
                    report(isOskField(e.target));
                }, true);

                document.addEventListener('focusout', function(e) {
                    report(false);
                }, true);
            })();
        """.trimIndent()
    }
}
