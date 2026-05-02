package com.orion.inference

import com.orion.core.PerceptionResult
import com.orion.core.Plan

sealed class StepResult {
    data class Resolved(val perception: PerceptionResult, val plan: Plan) : StepResult()
    object Escalate : StepResult()
}

interface InferenceStep {
    val name: String
    suspend fun run(context: CycleContext): StepResult
}
