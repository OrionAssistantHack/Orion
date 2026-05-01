package com.orion

import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @Test
    fun onCreate_doesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }

    @Test
    fun defaultCategory_isRides() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("rides", activity.selectedCategory)
            }
        }
    }

    @Test
    fun ridesCards_containUberPackage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cards = activity.presetCardsFor("rides")
                assertTrue(cards.any { it.packageName == "com.ubercab" })
            }
        }
    }

    @Test
    fun ridesCards_containLyftPackage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cards = activity.presetCardsFor("rides")
                assertTrue(cards.any { it.packageName == "me.lyft.android" })
            }
        }
    }

    @Test
    fun foodCards_containDoorDash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cards = activity.presetCardsFor("food")
                assertTrue(cards.any { it.packageName == "com.dd.doordash" })
            }
        }
    }

    @Test
    fun foodCards_containUberEats() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cards = activity.presetCardsFor("food")
                assertTrue(cards.any { it.packageName == "com.ubercab.eats" })
            }
        }
    }

    @Test
    fun setStatusPill_ready_doesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setStatusPill("ready")
            }
        }
    }

    @Test
    fun setStatusPill_running_doesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setStatusPill("running")
            }
        }
    }

    @Test
    fun ridesCards_containComparisonCard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cards = activity.presetCardsFor("rides")
                assertTrue(cards.any { it.packageName == null })
            }
        }
    }

    @Test
    fun foodCards_containGrubhub() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cards = activity.presetCardsFor("food")
                assertTrue(cards.any { it.packageName == "com.grubhub.android" })
            }
        }
    }
}
