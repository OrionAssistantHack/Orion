package com.orion.inference

import android.graphics.Rect
import android.media.Image
import com.orion.core.PerceptionResult
import com.orion.core.Plan
import com.orion.core.ScreenPhase
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InferencePipelineTest {

    private val fakeContext = CycleContext(
        image = mockk<Image>(relaxed = true),
        nodes = emptyList(),
        goal = "test goal",
        screenW = 1080,
        screenH = 2400,
        appPackage = "com.example",
        retryContext = "",
        previousAction = "",
        frameNum = 1,
    )

    private val resolvedPerception = PerceptionResult(ScreenPhase.UNKNOWN, emptyMap(), null, 0.9f, "{}")
    private val resolvedPlan = Plan("done", emptyList())

    @Test
    fun `pipeline returns result from first step when it resolves`() = runBlocking {
        val step = object : InferenceStep {
            override val name = "AlwaysResolve"
            override suspend fun run(context: CycleContext) =
                StepResult.Resolved(resolvedPerception, resolvedPlan)
        }
        val (_, plan) = InferencePipeline(listOf(step)).run(fakeContext)
        assertEquals(resolvedPlan, plan)
    }

    @Test
    fun `pipeline falls through to second step when first escalates`() = runBlocking {
        val step1 = object : InferenceStep {
            override val name = "Escalator"
            override suspend fun run(context: CycleContext) = StepResult.Escalate
        }
        val step2 = object : InferenceStep {
            override val name = "Resolver"
            override suspend fun run(context: CycleContext) =
                StepResult.Resolved(resolvedPerception, resolvedPlan)
        }
        val (_, plan) = InferencePipeline(listOf(step1, step2)).run(fakeContext)
        assertEquals(resolvedPlan, plan)
    }

    @Test
    fun `pipeline throws when all steps escalate`() {
        val alwaysEscalate = object : InferenceStep {
            override val name = "NeverResolve"
            override suspend fun run(context: CycleContext) = StepResult.Escalate
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { InferencePipeline(listOf(alwaysEscalate)).run(fakeContext) }
        }
    }

    @Test
    fun `pipeline skips remaining steps after resolution`() = runBlocking {
        var secondStepRan = false
        val step1 = object : InferenceStep {
            override val name = "Resolver"
            override suspend fun run(context: CycleContext) =
                StepResult.Resolved(resolvedPerception, resolvedPlan)
        }
        val step2 = object : InferenceStep {
            override val name = "ShouldNotRun"
            override suspend fun run(context: CycleContext): StepResult {
                secondStepRan = true
                return StepResult.Escalate
            }
        }
        InferencePipeline(listOf(step1, step2)).run(fakeContext)
        assertEquals(false, secondStepRan)
    }
}
