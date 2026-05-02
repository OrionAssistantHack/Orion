package com.orion.inference

import android.util.Log
import com.orion.core.PerceptionResult
import com.orion.core.Plan

private const val TAG = "Orion.InferencePipeline"

class InferencePipeline(private val steps: List<InferenceStep>) {

    suspend fun run(context: CycleContext): Pair<PerceptionResult, Plan> {
        for (step in steps) {
            Log.d(TAG, "Frame #${context.frameNum} running step: ${step.name}")
            when (val result = step.run(context)) {
                is StepResult.Resolved -> {
                    Log.d(TAG, "Frame #${context.frameNum} resolved by ${step.name}")
                    return result.perception to result.plan
                }
                StepResult.Escalate -> {
                    Log.d(TAG, "Frame #${context.frameNum} ${step.name} escalated — trying next step")
                }
            }
        }
        error("InferencePipeline exhausted all steps without resolution for frame #${context.frameNum}")
    }
}
