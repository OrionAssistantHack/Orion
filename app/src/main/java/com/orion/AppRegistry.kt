package com.orion

import android.content.Context
import com.orion.core.AppCategory
import com.orion.core.KnownApp

object AppRegistry {

    // Add new apps here. installedFor() filters to what's on-device automatically.
    val ALL: List<KnownApp> = listOf(

        // ── RIDES ────────────────────────────────────────────────────────
        KnownApp(
            packageName = "com.ubercab",
            displayName = "Uber",
            deepLinkScheme = "uber://",
            supportsSetText = true,
            destinationFieldHint = "Where to?",
            category = AppCategory.RIDES,
        ),
        KnownApp(
            packageName = "com.lyft.android",
            displayName = "Lyft",
            deepLinkScheme = "lyft://",
            supportsSetText = false,
            destinationFieldHint = "Enter destination",
            category = AppCategory.RIDES,
        ),
        KnownApp(
            packageName = "me.lyft.android",
            displayName = "Lyft",
            deepLinkScheme = "lyft://",
            supportsSetText = false,
            destinationFieldHint = "Enter destination",
            category = AppCategory.RIDES,
        ),
        KnownApp(
            packageName = "com.waymo.carapp",
            displayName = "Waymo",
            deepLinkScheme = "waymo://",
            supportsSetText = false,
            destinationFieldHint = "Set destination",
            category = AppCategory.RIDES,
        ),

        // ── FOOD DELIVERY ────────────────────────────────────────────────
        KnownApp(
            packageName = "com.ubercab.eats",
            displayName = "Uber Eats",
            deepLinkScheme = null,
            supportsSetText = true,
            destinationFieldHint = "Search Uber Eats",
            category = AppCategory.FOOD_DELIVERY,
        ),
        KnownApp(
            packageName = "com.dd.doordash",
            displayName = "DoorDash",
            deepLinkScheme = null,
            supportsSetText = true,
            destinationFieldHint = "Search",
            category = AppCategory.FOOD_DELIVERY,
        ),
        KnownApp(
            packageName = "com.grubhub.android",
            displayName = "Grubhub",
            deepLinkScheme = null,
            supportsSetText = true,
            destinationFieldHint = "Search Grubhub",
            category = AppCategory.FOOD_DELIVERY,
        ),
    )

    // Returns only the apps in the given category that are installed on this device.
    // Deduplicate by displayName so both Lyft package variants never both appear.
    fun installedFor(context: Context, category: AppCategory): List<KnownApp> {
        val pm = context.packageManager
        return ALL
            .filter { it.category == category }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .distinctBy { it.displayName }
    }
}
