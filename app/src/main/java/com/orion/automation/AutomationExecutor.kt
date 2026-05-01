package com.orion.automation

import android.graphics.Bitmap
import com.orion.core.ExecutionResult

interface AutomationExecutor {
    fun tapNode(nodeText: String): ExecutionResult
    fun dispatchTap(x: Float, y: Float): ExecutionResult
    fun isScreenSecure(bitmap: Bitmap): Boolean
}
