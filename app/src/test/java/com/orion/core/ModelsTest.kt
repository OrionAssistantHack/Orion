package com.orion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun executionResult_defaults() {
        val r = ExecutionResult(success = true)
        assertTrue(r.success)
        assertFalse(r.fallbackUsed)
        assertNull(r.errorCode)
    }

    @Test
    fun tapTarget_boundsHintIsListOfInt() {
        val t = TapTarget("Search", listOf(0, 100, 300, 200))
        assertEquals(listOf(0, 100, 300, 200), t.boundsHint)
    }

    @Test
    fun tapTarget_boundsHintNullByDefault() {
        assertNull(TapTarget("Search").boundsHint)
    }

    @Test
    fun planAction_defaults() {
        val a = PlanAction(type = "tap_node")
        assertNull(a.nodeText)
        assertNull(a.nodeIndex)
        assertNull(a.waitForPhase)
    }

    @Test
    fun planAction_waitForPhaseIsString() {
        val a = PlanAction(type = "tap_node", waitForPhase = "HOME")
        assertEquals("HOME", a.waitForPhase)
    }

    @Test
    fun orionMode_values() {
        val modes = OrionMode.values()
        assertTrue(modes.contains(OrionMode.IDLE))
        assertTrue(modes.contains(OrionMode.WATCHING))
        assertTrue(modes.contains(OrionMode.COMPARING))
        assertTrue(modes.contains(OrionMode.READY))
    }

    @Test
    fun plan_construction() {
        val plan = Plan("tap Search", listOf(PlanAction("tap_node", nodeText = "Search")))
        assertEquals("tap Search", plan.summaryForUser)
        assertEquals(1, plan.actions.size)
    }
}
