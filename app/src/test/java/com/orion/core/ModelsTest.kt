package com.orion.core

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {

    @Test
    fun `ExecutionResult defaults fallbackUsed to false and errorCode to null`() {
        val result = ExecutionResult(success = true)
        assertFalse(result.fallbackUsed)
        assertNull(result.errorCode)
    }

    @Test
    fun `ExecutionResult carries errorCode on failure`() {
        val result = ExecutionResult(success = false, errorCode = "NODE_NOT_FOUND")
        assertFalse(result.success)
        assertEquals("NODE_NOT_FOUND", result.errorCode)
    }

    @Test
    fun `Plan holds summary and action list`() {
        val action = PlanAction(type = "tap_node", nodeIndex = 2, nodeText = "Sign in")
        val plan = Plan(summaryForUser = "Tapping sign-in button", actions = listOf(action))
        assertEquals("Tapping sign-in button", plan.summaryForUser)
        assertEquals(1, plan.actions.size)
        assertEquals("tap_node", plan.actions[0].type)
        assertEquals(2, plan.actions[0].nodeIndex)
    }

    @Test
    fun `PlanAction type_text carries text payload`() {
        val action = PlanAction(type = "type_text", nodeIndex = 0, text = "Hello world")
        assertEquals("type_text", action.type)
        assertEquals("Hello world", action.text)
    }

    @Test
    fun `ScreenPhase enum contains expected values`() {
        val phases = ScreenPhase.values().map { it.name }
        assertTrue(phases.containsAll(listOf("UNKNOWN", "HOME", "SEARCH_INPUT", "FARE_ESTIMATE", "CONFIRMATION")))
    }

    @Test
    fun `TapTarget stores nodeText and optional boundsHint`() {
        val t1 = TapTarget(nodeText = "Submit")
        assertNull(t1.boundsHint)
        val t2 = TapTarget(nodeText = "Submit", boundsHint = "[100,200][300,400]")
        assertEquals("[100,200][300,400]", t2.boundsHint)
    }

    @Test
    fun `PerceptionResult carries all fields`() {
        val pr = PerceptionResult(
            screenPhase = ScreenPhase.HOME,
            extractedData = mapOf("fare" to "12.50"),
            tapTarget = TapTarget("Confirm"),
            confidence = 0.9f,
            rawDescription = "Home screen visible"
        )
        assertEquals(ScreenPhase.HOME, pr.screenPhase)
        assertEquals("12.50", pr.extractedData["fare"])
        assertEquals(0.9f, pr.confidence)
    }
}
