package org.openlife.app.ui

import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * Design §3/§8: FLAG_SECURE on every content-bearing window. It helps
 * prevent supported screenshots and non-secure display output, but is not
 * universal protection against a hostile device (design §3).
 *
 * Review U-01: the window is also kept out of autofill structures. Compose's
 * AndroidComposeView reports itself important for autofill (it overrides
 * getImportantForAutofill to return IMPORTANT_FOR_AUTOFILL_YES), so the flag
 * cannot be cleared on the Compose view itself. The platform's
 * View.isImportantForAutofill() returns false for any view below an ancestor
 * marked IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, so marking the
 * window's root removes every view in it from the structure an autofill
 * service receives. Every Activity calls this (SecureWindowTest).
 */
fun ComponentActivity.applySecureWindow() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    excludeWindowFromAutofill(window.decorView)
}

/**
 * Marks the root of [view]'s window as not important for autofill, with its
 * descendants. Used for activity windows and, from ContentTextField, for the
 * dialog window a content field is shown in.
 */
fun excludeWindowFromAutofill(view: View) {
    view.rootView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
}
