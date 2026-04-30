package com.orion

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class OrionAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val debounceJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    @Volatile var onCaptureRequested: ((List<AccessibilityNodeInfo>) -> Unit)? = null

    companion object {
        private const val TAG = "OrionAccessibilityService"

        val watchedPackages = setOf(
            "com.ubercab",
            "me.lyft.android",
            "com.google.aiedge.gallery"
        )

        @Volatile
        var instance: OrionAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in watchedPackages) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        debounceJobs[pkg]?.cancel()
        val cb = onCaptureRequested
        debounceJobs[pkg] = serviceScope.launch {
            delay(500)
            Log.d(TAG, "Capture requested for $pkg")
            val root = rootInActiveWindow ?: return@launch
            cb?.invoke(collectInteractiveNodes(root))
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun collectInteractiveNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(node: AccessibilityNodeInfo) {
            if (node.isClickable || node.isEditable) result.add(node)
            repeat(node.childCount) { i -> node.getChild(i)?.let { traverse(it) } }
        }
        traverse(root)
        return result
    }
}
