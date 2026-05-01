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

    @Test
    fun registry_noTwoRideAppsShareDisplayName_inAll() {
        // Both Lyft package variants exist in ALL, but installedFor() deduplicates by displayName.
        // This test verifies that ALL itself has no accidental duplicate displayNames *beyond*
        // the intentional Lyft alias pair.
        val rideApps = AppRegistry.ALL.filter { it.category == AppCategory.RIDES }
        val countByName = rideApps.groupBy { it.displayName }
        // Only "Lyft" is allowed to have 2 entries (me.lyft.android + com.lyft.android aliases).
        countByName.forEach { (name, apps) ->
            assertTrue(
                "Unexpected duplicate displayName '$name' in RIDES (only Lyft aliases are allowed)",
                apps.size <= 2 && (apps.size == 1 || name == "Lyft")
            )
        }
    }
}
