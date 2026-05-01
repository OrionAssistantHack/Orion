package com.orion.automation

import android.graphics.Bitmap
import com.orion.core.ExecutionResult

interface AutomationExecutor {
    fun tapNode(nodeText: String): ExecutionResult
    fun dispatchTap(x: Float, y: Float): ExecutionResult
    fun swipe(direction: String, screenWidth: Int, screenHeight: Int): ExecutionResult
    fun pressHome(): ExecutionResult
    fun isScreenSecure(bitmap: Bitmap): Boolean
}
