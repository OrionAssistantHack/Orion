package com.orion

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.orion.automation.AccessibilityAutomationExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OrionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Orion.A11y"
        private val WATCHED_PACKAGES = setOf("com.ubercab", "me.lyft.android", "com.google.aiedge.gallery")

        @Volatile
        var instance: OrionAccessibilityService? = null
            private set

        @Volatile
        var currentPackage: String = ""

        @Volatile
        var lastAppPackage: String = ""

        private val SYSTEM_PACKAGES = setOf("com.android.systemui", "android")

        private var lastTriggerMs: Long = 0L
    }

    lateinit var executor: AccessibilityAutomationExecutor
        private set

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        instance = this
        executor = AccessibilityAutomationExecutor(this)
        Log.i(TAG, "OrionAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        currentPackage = pkg
        if (pkg !in SYSTEM_PACKAGES) lastAppPackage = pkg
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg !in WATCHED_PACKAGES) return
                Log.i(TAG, "Window changed → package=$pkg class=${event.className}")
                serviceScope.launch {
                    delay(500L)
                    ScreenCaptureService.triggerCapture()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg !in WATCHED_PACKAGES) return
                val now = System.currentTimeMillis()
                if (now - lastTriggerMs >= 500L) {
                    lastTriggerMs = now
                    ScreenCaptureService.triggerCapture()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "OrionAccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
