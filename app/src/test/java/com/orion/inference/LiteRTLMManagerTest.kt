package com.orion.inference

import com.orion.core.ScreenPhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiteRTLMManagerTest {

    @Test
    fun `parseResponse extracts tap_node action`() {
        val raw = """{"action":{"type":"tap_node","nodeIndex":2,"nodeText":"Book","text":null},"screenPhase":"HOME","extractedData":{},"confidence":0.85,"summaryForUser":"Tapping Book"}"""
        val (perception, plan) = LiteRTLMManager.parseResponse(raw)
        assertEquals(ScreenPhase.HOME, perception.screenPhase)
        assertEquals("Tapping Book", plan.summaryForUser)
        assertEquals("tap_node", plan.actions[0].type)
        assertEquals(1, plan.actions[0].nodeIndex)  // 2 - 1 = 1 (0-based)
        assertEquals("Book", plan.actions[0].nodeText)
    }

    @Test
    fun `parseResponse extracts type_text action`() {
        val raw = """{"action":{"type":"type_text","nodeIndex":1,"nodeText":"Where to?","text":"JFK Airport"},"screenPhase":"SEARCH_INPUT","extractedData":{},"confidence":0.9,"summaryForUser":"Typing destination"}"""
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
    fun `parseResponse treats none type as empty action`() {
        val raw = """{"action":{"type":"none","nodeIndex":null,"nodeText":null,"text":null},"screenPhase":"UNKNOWN","extractedData":{},"confidence":0.5,"summaryForUser":"Waiting"}"""
        val (_, plan) = LiteRTLMManager.parseResponse(raw)
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `parseResponse handles coordinate tap`() {
        val raw = """{"action":{"type":"tap_node","x":540.0,"y":960.0},"screenPhase":"HOME","extractedData":{},"confidence":0.7,"summaryForUser":"Coordinate tap"}"""
        val (_, plan) = LiteRTLMManager.parseResponse(raw)
        assertEquals(540f, plan.actions[0].x)
        assertEquals(960f, plan.actions[0].y)
    }

    @Test
    fun `parseResponse extracts confidence and extracted data`() {
        val raw = """{"action":{"type":"none"},"screenPhase":"FARE_ESTIMATE","extractedData":{"price":"$12.50","eta":"5 min"},"confidence":0.95,"summaryForUser":"Fare shown"}"""
        val (perception, plan) = LiteRTLMManager.parseResponse(raw)
        assertEquals(ScreenPhase.FARE_ESTIMATE, perception.screenPhase)
        assertEquals(0.95f, perception.confidence, 0.01f)
        assertEquals("$12.50", perception.extractedData["price"])
        assertTrue(plan.actions.isEmpty())
    }
}
