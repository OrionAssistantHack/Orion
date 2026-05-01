package com.orion.inference

import com.orion.core.ScreenPhase
import org.junit.Assert.*
import org.junit.Test

class LiteRTLMManagerTest {

    @Test
    fun `parseResponse extracts tap_node action`() {
        val raw = """{"screenPhase":"HOME","extractedData":{},"confidence":0.85,"summaryForUser":"Tapping Book","actions":[{"type":"tap_node","nodeIndex":2,"nodeText":"Book"}]}"""
        val (perception, plan) = LiteRTLMManager.parseResponse(raw)
        assertEquals(ScreenPhase.HOME, perception.screenPhase)
        assertEquals("Tapping Book", plan.summaryForUser)
        assertEquals("tap_node", plan.actions[0].type)
        assertEquals(1, plan.actions[0].nodeIndex)  // 2 - 1 = 1 (0-based)
        assertEquals("Book", plan.actions[0].nodeText)
    }

    @Test
    fun `parseResponse extracts type_text action`() {
        val raw = """{"screenPhase":"SEARCH_INPUT","extractedData":{},"confidence":0.9,"summaryForUser":"Typing destination","actions":[{"type":"type_text","nodeIndex":1,"text":"JFK Airport"}]}"""
        val (_, plan) = LiteRTLMManager.parseResponse(raw)
        assertEquals("type_text", plan.actions[0].type)
        assertEquals("JFK Airport", plan.actions[0].text)
        assertEquals(0, plan.actions[0].nodeIndex)  // 1 - 1 = 0
    }

    @Test
    fun `parseResponse returns empty plan on malformed JSON`() {
        val (_, plan) = LiteRTLMManager.parseResponse("not json")
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `parseResponse handles multiple actions`() {
        val raw = """{"screenPhase":"HOME","extractedData":{},"confidence":0.8,"summaryForUser":"Multi-step","actions":[{"type":"tap_node","nodeIndex":1,"nodeText":"Search"},{"type":"type_text","nodeIndex":2,"text":"Airport"},{"type":"tap_node","nodeIndex":4,"nodeText":"Go"}]}"""
        val (_, plan) = LiteRTLMManager.parseResponse(raw)
        assertEquals(3, plan.actions.size)
        assertEquals("Airport", plan.actions[1].text)
    }

    @Test
    fun `parseResponse handles coordinate tap`() {
        val raw = """{"screenPhase":"HOME","extractedData":{},"confidence":0.7,"summaryForUser":"Coordinate tap","actions":[{"type":"tap_node","x":540.0,"y":960.0}]}"""
        val (_, plan) = LiteRTLMManager.parseResponse(raw)
        assertEquals(540f, plan.actions[0].x)
        assertEquals(960f, plan.actions[0].y)
    }

    @Test
    fun `parseResponse extracts confidence and extracted data`() {
        val raw = """{"screenPhase":"FARE_ESTIMATE","extractedData":{"price":"$12.50","eta":"5 min"},"confidence":0.95,"summaryForUser":"Fare shown","actions":[]}"""
        val (perception, plan) = LiteRTLMManager.parseResponse(raw)
        assertEquals(ScreenPhase.FARE_ESTIMATE, perception.screenPhase)
        assertEquals(0.95f, perception.confidence, 0.01f)
        assertEquals("$12.50", perception.extractedData["price"])
        assertTrue(plan.actions.isEmpty())
    }
}
