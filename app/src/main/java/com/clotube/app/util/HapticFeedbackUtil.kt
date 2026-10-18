package com.clotube.app.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Native Haptic Feedback Helpers for crisp tactile feedback across Clotube.
 *
 * Utilizes low-latency View.performHapticFeedback with HapticFeedbackConstants.CONFIRM / CONTEXT_CLICK
 * and KEYBOARD_TAP / VIRTUAL_KEY, prioritizing FLAG_IGNORE_GLOBAL_SETTING for punchy Nothing OS /
 * Android 13+ tactile actuation.
 */
@Suppress("DEPRECATION")
fun View.triggerClickHaptic() {
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.CONTEXT_CLICK
    }
    if (!performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)) {
        performHapticFeedback(constant)
    }
}

@Suppress("DEPRECATION")
fun View.triggerNavigationHaptic() {
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        HapticFeedbackConstants.KEYBOARD_TAP
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }
    if (!performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)) {
        performHapticFeedback(constant)
    }
}

/**
 * Backward-compatible alias for bottom navigation tab ticks.
 */
fun View.triggerTabTickHaptic() = triggerNavigationHaptic()

