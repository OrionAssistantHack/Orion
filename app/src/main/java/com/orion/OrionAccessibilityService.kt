package com.orion

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.orion.automation.AccessibilityAutomationExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OrionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Orion.A11y"
        private const val DEBOUNCE_MS = 700L
        private const val MAX_WAIT_MS = 3000L
        private val WATCHED_PACKAGES = setOf("com.ubercab", "me.lyft.android", "com.google.aiedge.gallery")
        private val SYSTEM_PACKAGES = setOf("com.android.systemui", "android")

        @Volatile var instance: OrionAccessibilityService? = null
            private set
        @Volatile var currentPackage: String = ""
        @Volatile var lastAppPackage: String = ""
    }

    lateinit var executor: AccessibilityAutomationExecutor
        private set

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var debounceJob: Job? = null
    private var lastTriggerMs: Long = 0L

    override fun onServiceConnected() {
        instance = this
        executor = AccessibilityAutomationExecutor(this)
        Log.i(TAG, "OrionAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        currentPackage = pkg
        if (pkg !in SYSTEM_PACKAGES) lastAppPackage = pkg
        if (pkg !in WATCHED_PACKAGES) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()

        // If max-wait exceeded, trigger immediately regardless of ongoing events
        if (now - lastTriggerMs >= MAX_WAIT_MS) {
            debounceJob?.cancel()
            debounceJob = null
            lastTriggerMs = now
            Log.d(TAG, "Max wait exceeded for $pkg — triggering capture now")
            ScreenCaptureService.triggerCapture()
            return
        }

        // Debounce: reset countdown on every event
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            lastTriggerMs = System.currentTimeMillis()
            Log.d(TAG, "Window settled ($pkg) — triggering capture")
            ScreenCaptureService.triggerCapture()
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
