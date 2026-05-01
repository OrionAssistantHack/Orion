package com.orion

import com.orion.core.AppCategory
import org.junit.Assert.*
import org.junit.Test

class AppRegistryTest {

    @Test
    fun registry_hasAtLeastOneRideApp() {
        val rideApps = AppRegistry.ALL.filter { it.category == AppCategory.RIDES }
        assertTrue("No ride apps in registry", rideApps.isNotEmpty())
    }

    @Test
    fun registry_hasAtLeastOneFoodApp() {
        val foodApps = AppRegistry.ALL.filter { it.category == AppCategory.FOOD_DELIVERY }
        assertTrue("No food apps in registry", foodApps.isNotEmpty())
    }

    @Test
    fun registry_noBlankPackageNames() {
        AppRegistry.ALL.forEach { app ->
            assertTrue("Blank packageName for ${app.displayName}", app.packageName.isNotBlank())
        }
    }

    @Test
    fun registry_noBlankDisplayNames() {
        AppRegistry.ALL.forEach { app ->
            assertTrue("Blank displayName for ${app.packageName}", app.displayName.isNotBlank())
        }
    }

    @Test
    fun registry_uberIsInRides() {
        assertTrue(AppRegistry.ALL.any {
            it.packageName == "com.ubercab" && it.category == AppCategory.RIDES
        })
    }

    @Test
    fun registry_doordashIsInFood() {
        assertTrue(AppRegistry.ALL.any {
            it.packageName == "com.dd.doordash" && it.category == AppCategory.FOOD_DELIVERY
        })
    }
}
