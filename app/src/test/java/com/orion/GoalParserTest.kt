package com.orion

import com.orion.core.ParsedGoal
import org.junit.Assert.*
import org.junit.Test

class GoalParserTest {

    @Test
    fun parse_bookACabTo_extractsDestination() {
        val result = parseGoal("Book a cab to SFO Airport") as? ParsedGoal.RideRequest
        assertNotNull(result)
        assertEquals("SFO Airport", result!!.destination)
    }

    @Test
    fun parse_getARideTo_extractsDestination() {
        val result = parseGoal("Get a ride to downtown") as? ParsedGoal.RideRequest
        assertNotNull(result)
        assertEquals("downtown", result!!.destination)
    }

    @Test
    fun parse_bookMeAnUberTo_extractsDestination() {
        val result = parseGoal("Book me an Uber to the train station") as? ParsedGoal.RideRequest
        assertNotNull(result)
        assertEquals("the train station", result!!.destination)
        assertEquals("com.ubercab", result.singleApp?.packageName)
    }

    @Test
    fun parse_callALyftTo_extractsDestination() {
        val result = parseGoal("Call a Lyft to 123 Main St") as? ParsedGoal.RideRequest
        assertNotNull(result)
        assertEquals("123 Main St", result!!.destination)
        assertEquals("com.lyft.android", result.singleApp?.packageName)
    }

    @Test
    fun parse_orderBurgerFromShakeShack_extractsRestaurantAndItem() {
        val result = parseGoal("Order a burger from Shake Shack") as? ParsedGoal.FoodOrder
        assertNotNull(result)
        assertEquals("Shake Shack", result!!.restaurant)
        assertEquals("burger", result.item)
    }

    @Test
    fun parse_orderFromRestaurant_noItem_returnsNullItem() {
        val result = parseGoal("Order from Chipotle") as? ParsedGoal.FoodOrder
        assertNotNull(result)
        assertEquals("Chipotle", result!!.restaurant)
        assertNull(result.item)
    }

    @Test
    fun parse_getDeliveryFrom_extractsRestaurant() {
        val result = parseGoal("Get delivery from McDonald's") as? ParsedGoal.FoodOrder
        assertNotNull(result)
        assertEquals("McDonald's", result!!.restaurant)
    }

    @Test
    fun parse_unrelated_returnsNull() {
        assertNull(parseGoal("Open Uber and find drivers near me"))
    }

    @Test
    fun parse_empty_returnsNull() {
        assertNull(parseGoal(""))
    }

    @Test
    fun parse_bookACabTo_singleAppIsNull() {
        val result = parseGoal("Book a cab to SFO Airport") as? ParsedGoal.RideRequest
        assertNotNull(result)
        assertNull(result!!.singleApp)
    }

    @Test
    fun parse_getARideTo_singleAppIsNull() {
        val result = parseGoal("Get a ride to downtown") as? ParsedGoal.RideRequest
        assertNotNull(result)
        assertNull(result!!.singleApp)
    }
}
