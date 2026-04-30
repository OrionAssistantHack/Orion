package com.orion.inference

import org.junit.Assert.*
import org.junit.Test

class LiteRTLMManagerTest {

    @Test
    fun `buildPrompt includes goal`() {
        val prompt = LiteRTLMManager.buildPrompt(
            goal = "Book a ride to the airport",
            nodeList = "0: Search\n1: Confirm",
            retryContext = null
        )
        assertTrue(prompt.contains("Book a ride to the airport"))
        assertTrue(prompt.contains("0: Search"))
    }

    @Test
    fun `buildPrompt includes retryContext when provided`() {
        val prompt = LiteRTLMManager.buildPrompt(
            goal = "Book a ride",
            nodeList = "0: Book",
            retryContext = "Node 0 not found"
        )
        assertTrue(prompt.contains("Node 0 not found"))
    }

    @Test
    fun `buildPrompt omits retry section when retryContext is null`() {
        val prompt = LiteRTLMManager.buildPrompt("Book", "0: Book", null)
        assertFalse(prompt.contains("Previous attempt failed"))
    }

    @Test
    fun `parseResponse extracts tap_node action`() {
        val raw = """
            {
              "screenPhase": "HOME",
              "extractedData": {},
              "confidence": 0.85,
              "summaryForUser": "Tapping Book",
              "actions": [{"type": "tap_node", "nodeIndex": 2, "nodeText": "Book"}]
            }
        """.trimIndent()
        val plan = LiteRTLMManager.parseResponse(raw)
        assertEquals("Tapping Book", plan.summaryForUser)
        assertEquals("tap_node", plan.actions[0].type)
        assertEquals(2, plan.actions[0].nodeIndex)
        assertEquals("Book", plan.actions[0].nodeText)
    }

    @Test
    fun `parseResponse extracts type_text action`() {
        val raw = """
            {
              "screenPhase": "SEARCH_INPUT",
              "extractedData": {},
              "confidence": 0.9,
              "summaryForUser": "Typing destination",
              "actions": [{"type": "type_text", "nodeIndex": 0, "text": "JFK Airport"}]
            }
        """.trimIndent()
        val plan = LiteRTLMManager.parseResponse(raw)
        assertEquals("type_text", plan.actions[0].type)
        assertEquals("JFK Airport", plan.actions[0].text)
    }

    @Test
    fun `parseResponse returns empty plan on malformed JSON`() {
        val plan = LiteRTLMManager.parseResponse("not json")
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `parseResponse handles multiple actions`() {
        val raw = """
            {
              "screenPhase": "HOME", "extractedData": {}, "confidence": 0.8,
              "summaryForUser": "Multi-step",
              "actions": [
                {"type": "tap_node", "nodeIndex": 0, "nodeText": "Search"},
                {"type": "type_text", "nodeIndex": 1, "text": "Airport"},
                {"type": "tap_node", "nodeIndex": 3, "nodeText": "Go"}
              ]
            }
        """.trimIndent()
        val plan = LiteRTLMManager.parseResponse(raw)
        assertEquals(3, plan.actions.size)
        assertEquals("Airport", plan.actions[1].text)
    }

    @Test
    fun `parseResponse handles coordinate tap`() {
        val raw = """
            {
              "screenPhase": "HOME", "extractedData": {}, "confidence": 0.7,
              "summaryForUser": "Coordinate tap",
              "actions": [{"type": "tap_node", "x": 540.0, "y": 960.0}]
            }
        """.trimIndent()
        val plan = LiteRTLMManager.parseResponse(raw)
        assertEquals(540f, plan.actions[0].x)
        assertEquals(960f, plan.actions[0].y)
    }
}
