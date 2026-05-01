package com.orion.automation

import android.graphics.Bitmap
import com.orion.core.ExecutionResult

class MockAutomationExecutor(
    var secureResult: Boolean = false
) : AutomationExecutor {

    var lastTappedText: String? = null
    var lastTapX: Float = 0f
    var lastTapY: Float = 0f
    var lastBitmap: Bitmap? = null
    var lastSwipeDirection: String? = null
    var pressHomeCount: Int = 0

    override fun tapNode(nodeText: String): ExecutionResult {
        lastTappedText = nodeText
        return ExecutionResult(true)
    }

    override fun dispatchTap(x: Float, y: Float): ExecutionResult {
        lastTapX = x
        lastTapY = y
        return ExecutionResult(true)
    }

    override fun swipe(direction: String, screenWidth: Int, screenHeight: Int): ExecutionResult {
        lastSwipeDirection = direction
        return ExecutionResult(true)
    }

    override fun pressHome(): ExecutionResult {
        pressHomeCount++
        return ExecutionResult(true)
    }

    override fun isScreenSecure(bitmap: Bitmap): Boolean {
        lastBitmap = bitmap
        return secureResult
    }
}
