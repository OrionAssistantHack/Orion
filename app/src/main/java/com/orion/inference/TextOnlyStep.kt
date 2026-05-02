package com.orion.inference

import android.util.Log

private const val TAG = "Orion.TextOnlyStep"
private const val CONFIDENCE_THRESHOLD = 0.7f

class TextOnlyStep(private val lm: LiteRTLMManager) : InferenceStep {

    override val name = "TextOnly"

    override suspend fun run(context: CycleContext): StepResult {
        val (perception, plan) = lm.planFromNodes(
            goal = context.goal,
            nodes = context.nodes,
            screenWidth = context.screenW,
            screenHeight = context.screenH,
            appPackage = context.appPackage,
            retryContext = context.retryContext,
            previousAction = context.previousAction,
        )
        val firstAction = plan.actions.firstOrNull()
        val needsImage = firstAction?.type == "need_image" || perception.confidence < CONFIDENCE_THRESHOLD
        return if (needsImage) {
            Log.i(TAG, "Frame #${context.frameNum} escalating to vision — action=${firstAction?.type ?: "none"}, conf=${perception.confidence}")
            StepResult.Escalate
        } else {
            Log.i(TAG, "Frame #${context.frameNum} resolved by text-only — conf=${perception.confidence}")
            StepResult.Resolved(perception, plan)
        }
    }
}
