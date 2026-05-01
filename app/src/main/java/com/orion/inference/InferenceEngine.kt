package com.orion.inference

import android.graphics.Bitmap
import android.graphics.Rect
import com.orion.core.PerceptionResult
import com.orion.core.Plan

interface InferenceEngine {
    fun isReady(): Boolean
    fun getDescription(): String
    suspend fun perceiveAndPlan(
        bitmap: Bitmap,
        goal: String = "",
        nodes: List<Pair<String, Rect>> = emptyList(),
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        appPackage: String = "",
        retryContext: String = "",
        previousAction: String = "",
        keyboardVisible: Boolean = false,
        focusedInputIndex: Int = -1
    ): Pair<PerceptionResult, Plan>
    fun cleanup()
}
