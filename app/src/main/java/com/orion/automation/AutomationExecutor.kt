package com.orion.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.orion.core.ExecutionResult

interface AutomationExecutor {
    fun tapNode(node: AccessibilityNodeInfo): ExecutionResult
    fun dispatchTap(x: Float, y: Float): ExecutionResult
    fun typeText(node: AccessibilityNodeInfo, text: String): ExecutionResult
    fun isScreenSecure(): Boolean
}
