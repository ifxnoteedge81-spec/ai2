package com.personal.aicomputer.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * "Virtual Hands": an Android AccessibilityService. It does nothing by itself; it only exposes the
 * screen tree and gesture dispatch to HandsController, which in turn is only used by agent tools that
 * the user approved. The user must enable it manually in Android Settings > Accessibility.
 */
class HandsService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: no screen content is collected or stored in the background.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: HandsService? = null
    }
}
