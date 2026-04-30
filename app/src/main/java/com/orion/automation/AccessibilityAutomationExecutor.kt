package com.orion.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.orion.core.ExecutionResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AccessibilityAutomationExecutor(
    private val service: AccessibilityService
) : AutomationExecutor {

    override fun tapNode(node: AccessibilityNodeInfo): ExecutionResult =
        ExecutionResult(success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK))

    override fun dispatchTap(x: Float, y: Float): ExecutionResult {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var succeeded = false
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { succeeded = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription) { latch.countDown() }
        }, null)
        latch.await(2, TimeUnit.SECONDS)
        return ExecutionResult(success = succeeded)
    }

    override fun typeText(node: AccessibilityNodeInfo, text: String): ExecutionResult {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return ExecutionResult(success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
    }

    override fun isScreenSecure(): Boolean = false
}
