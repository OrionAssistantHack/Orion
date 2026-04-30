package com.orion.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.orion.core.ExecutionResult

class MockAutomationExecutor(
    private val tapResult: ExecutionResult = ExecutionResult(success = true),
    private val secureScreen: Boolean = false
) : AutomationExecutor {

    val tapNodeCalls = mutableListOf<AccessibilityNodeInfo>()
    val dispatchTapCalls = mutableListOf<Pair<Float, Float>>()
    val typeTextCalls = mutableListOf<Pair<AccessibilityNodeInfo, String>>()

    override fun tapNode(node: AccessibilityNodeInfo): ExecutionResult {
        tapNodeCalls.add(node)
        return tapResult
    }

    override fun dispatchTap(x: Float, y: Float): ExecutionResult {
        dispatchTapCalls.add(x to y)
        return tapResult
    }

    override fun typeText(node: AccessibilityNodeInfo, text: String): ExecutionResult {
        typeTextCalls.add(node to text)
        return tapResult
    }

    override fun isScreenSecure(): Boolean = secureScreen
}
