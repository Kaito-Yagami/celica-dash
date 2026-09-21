package com.celica.dash.remote

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses the package installer's buttons - Install, Update, Open, Done - when the dash has
 * asked for a build from the PC, so an update needs nobody in the car. It only ever touches
 * the installer's own dialogs, and only while an update of this app is on screen.
 */
class InstallClicker : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (!pkg.contains("packageinstaller")) return
        val root = rootInActiveWindow ?: return
        // only our own update: the install dialog names the app; the finished screen says so
        val ours = root.findAccessibilityNodeInfosByText("Celica").isNotEmpty() || root.findAccessibilityNodeInfosByText("celica").isNotEmpty()
        val finished = root.findAccessibilityNodeInfosByText("installed").isNotEmpty()
        if (!ours && !finished) return
        val labels = if (finished) listOf("Open", "OPEN", "Done", "DONE") else listOf("Install", "Update", "INSTALL", "UPDATE", "Continue", "Allow")
        for (label in labels) {
            val hits = root.findAccessibilityNodeInfosByText(label)
            for (n in hits) {
                var node: AccessibilityNodeInfo? = n
                while (node != null && !node.isClickable) node = node.parent
                if (node != null && node.isClickable && node.isEnabled) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
            }
        }
    }

    override fun onInterrupt() {}
}
