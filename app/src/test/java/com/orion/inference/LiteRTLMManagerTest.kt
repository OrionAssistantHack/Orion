package com.orion.inference

import com.orion.core.ScreenPhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.orion.inference.buildTextOnlyPrompt

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
        assertFalse(plan.goalReached)
    }

    @Test
    fun `parseResponse treats none type as empty action with goalReached`() {
        val raw = """{"action":{"type":"none","nodeIndex":null,"nodeText":null,"text":null},"screenPhase":"UNKNOWN","extractedData":{},"confidence":0.5,"summaryForUser":"Waiting"}"""
        val (_, plan) = LiteRTLMManager.parseResponse(raw)
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.goalReached)
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
        assertTrue(plan.goalReached)
    }

    @Test
    fun `buildTextOnlyPrompt does not reference screenshot`() {
        val prompt = buildTextOnlyPrompt("open lyft", emptyList(), 1080, 2400, "me.lyft.android", "", "")
        assertFalse("Must not say 'analyze this.*screenshot'", prompt.contains("Analyze this Android app screenshot"))
        assertTrue("Must say 'no screenshot'", prompt.contains("no screenshot"))
    }

    @Test
    fun `buildTextOnlyPrompt includes need_image in action type list`() {
        val prompt = buildTextOnlyPrompt("open lyft", emptyList(), 1080, 2400, "me.lyft.android", "", "")
        assertTrue(prompt.contains("need_image"))
    }

    @Test
    fun `buildTextOnlyPrompt includes goal and app package`() {
        val prompt = buildTextOnlyPrompt("book a ride", emptyList(), 1080, 2400, "com.ubercab", "", "")
        assertTrue(prompt.contains("book a ride"))
        assertTrue(prompt.contains("com.ubercab"))
    }

    @Test
    fun `buildTextOnlyPrompt includes retry prefix when retryContext provided`() {
        val prompt = buildTextOnlyPrompt("goal", emptyList(), 0, 0, "", "previous node not found", "")
        assertTrue(prompt.startsWith("IMPORTANT - previous node not found"))
    }

    @Test
    fun `parseResponse parses need_image action`() {
        val raw = """{"action":{"type":"need_image","nodeIndex":null,"nodeText":null,"text":null,"direction":null},"screenPhase":"UNKNOWN","extractedData":{},"confidence":0.3,"summaryForUser":"need image"}"""
        val (perception, plan) = LiteRTLMManager.parseResponse(raw)
        assertEquals("need_image", plan.actions[0].type)
        assertFalse(plan.goalReached)
        assertEquals(0.3f, perception.confidence, 0.01f)
    }
}
