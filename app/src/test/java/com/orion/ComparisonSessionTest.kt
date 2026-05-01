package com.orion

import com.orion.core.AppCategory
import com.orion.core.ComparisonSession
import com.orion.core.FareData
import com.orion.core.KnownApp
import com.orion.core.ParsedGoal
import org.junit.Assert.*
import org.junit.Test

class ComparisonSessionTest {

    private fun makeApp(pkg: String, name: String) = KnownApp(
        packageName = pkg, displayName = name, deepLinkScheme = null,
        supportsSetText = true, destinationFieldHint = "Search", category = AppCategory.RIDES
    )

    private fun rideSession(vararg pkgs: Pair<String, String>): ComparisonSession {
        val apps = pkgs.map { (pkg, name) -> makeApp(pkg, name) }
        return ComparisonSession(ParsedGoal.RideRequest("SFO Airport", "book a cab to SFO Airport"), apps)
    }

    @Test
    fun session_startsAtFirstApp() {
        val s = rideSession("com.ubercab" to "Uber", "com.lyft.android" to "Lyft")
        assertEquals("com.ubercab", s.currentApp!!.packageName) // session not complete yet
        assertFalse(s.isComplete)
    }

    @Test
    fun session_advancesAfterStoringFare() {
        val s = rideSession("com.ubercab" to "Uber", "com.lyft.android" to "Lyft")
        s.collectedFares["com.ubercab"] = FareData("$14.00", 6, 0.9f)
        s.advance()
        assertEquals("com.lyft.android", s.currentApp!!.packageName) // session not complete yet
        assertFalse(s.isComplete)
    }

    @Test
    fun session_isCompleteAfterAllApps() {
        val s = rideSession("com.ubercab" to "Uber", "com.lyft.android" to "Lyft", "com.waymo.carapp" to "Waymo")
        s.collectedFares["com.ubercab"] = FareData("$14.00", 6, 0.9f)
        s.advance()
        s.collectedFares["com.lyft.android"] = FareData("$12.50", 8, 0.95f)
        s.advance()
        s.collectedFares["com.waymo.carapp"] = FareData("$16.00", 5, 0.8f)
        s.advance()
        assertTrue(s.isComplete)
    }

    @Test
    fun session_cheapestApp_identifiesLowestPrice() {
        val s = rideSession("com.ubercab" to "Uber", "com.lyft.android" to "Lyft", "com.waymo.carapp" to "Waymo")
        s.collectedFares["com.ubercab"] = FareData("$14.00", 6, 0.9f)
        s.collectedFares["com.lyft.android"] = FareData("$12.50", 8, 0.95f)
        s.collectedFares["com.waymo.carapp"] = FareData("$16.00", 5, 0.8f)
        assertEquals("com.lyft.android", s.cheapestApp?.packageName)
    }

    @Test
    fun session_fastestApp_identifiesLowestEta() {
        val s = rideSession("com.ubercab" to "Uber", "com.lyft.android" to "Lyft", "com.waymo.carapp" to "Waymo")
        s.collectedFares["com.ubercab"] = FareData("$14.00", 6, 0.9f)
        s.collectedFares["com.lyft.android"] = FareData("$12.50", 8, 0.95f)
        s.collectedFares["com.waymo.carapp"] = FareData("$16.00", 5, 0.8f)
        assertEquals("com.waymo.carapp", s.fastestApp?.packageName)
    }

    @Test
    fun session_cheapestApp_nullWhenNoFaresCollected() {
        val s = rideSession("com.ubercab" to "Uber")
        assertNull(s.cheapestApp)
    }

    @Test
    fun session_singleApp_completeAfterOneAdvance() {
        val s = rideSession("com.ubercab" to "Uber")
        s.collectedFares["com.ubercab"] = FareData("$10.00", 5, 0.9f)
        s.advance()
        assertTrue(s.isComplete)
    }

    @Test
    fun session_cheapestApp_handlesDeliveryFeeStylePrice() {
        val s = rideSession("com.ubercab" to "Uber", "com.ubercab.eats" to "Uber Eats")
        s.collectedFares["com.ubercab"] = FareData("$14.00", 6, 0.9f)
        s.collectedFares["com.ubercab.eats"] = FareData("$3.99 delivery", null, 0.8f)
        assertEquals("com.ubercab.eats", s.cheapestApp?.packageName)
    }

    @Test
    fun session_fastestApp_nullWhenAllEtasNull() {
        val s = rideSession("com.ubercab" to "Uber", "com.lyft.android" to "Lyft")
        s.collectedFares["com.ubercab"] = FareData("$14.00", null, 0.9f)
        s.collectedFares["com.lyft.android"] = FareData("$12.50", null, 0.9f)
        assertNull(s.fastestApp)
    }
}
