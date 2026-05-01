package com.orion

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OnboardingActivityTest {

    private val prefs get() = ApplicationProvider.getApplicationContext<android.app.Application>()
        .getSharedPreferences("orion_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().remove("onboarding_complete").apply()
    }

    @After
    fun tearDown() {
        prefs.edit().remove("onboarding_complete").apply()
    }

    @Test
    fun firstLaunch_showsOnboardingWithoutFinishing() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals(0, activity.currentStep)
            }
        }
    }

    @Test
    fun alreadyOnboarded_launchesMainAndFinishes() {
        prefs.edit().putBoolean("onboarding_complete", true).apply()
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).create()
        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started?.component?.className)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun step1Cta_opensAccessibilitySettings() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onStep1CtaClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, started?.action)
            }
        }
    }

    @Test
    fun step2Cta_advancesToStep2() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.showStep(1)
                activity.onStep2CtaClick()
                assertEquals(2, activity.currentStep)
            }
        }
    }

    @Test
    fun showStep_setsCurrentStep() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.showStep(1)
                assertEquals(1, activity.currentStep)
                activity.showStep(0)
                assertEquals(0, activity.currentStep)
            }
        }
    }

    @Test
    fun step3Cta_opensOverlaySettings() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onStep3CtaClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, started?.action)
            }
        }
    }

    @Test
    fun advance_skipsStep3WhenOverlayAlreadyGranted() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Shadow canDrawOverlays to return true
                org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
                activity.showStep(1)
                activity.onStep2CtaClick() // calls advance(), next == 2, overlay granted → complete()
                assertTrue(activity.isFinishing)
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(MainActivity::class.java.name, started?.component?.className)
            }
        }
    }

    @Test
    fun onResume_onStep2_completesWhenOverlayGranted() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).create().start()
        val activity = controller.get()
        activity.showStep(2)
        controller.resume()
        assertTrue(activity.isFinishing)
    }
}
