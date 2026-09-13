package org.openlife.app.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * Design §3/§8: FLAG_SECURE on every content-bearing window. It helps
 * prevent supported screenshots and non-secure display output, but is not
 * universal protection against a hostile device (design §3).
 */
fun ComponentActivity.applySecureWindow() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
}
