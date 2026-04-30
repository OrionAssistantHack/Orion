package com.orion

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlagSecureActivityTest {

    @Test
    fun labelIsDisplayed() {
        ActivityScenario.launch(FlagSecureActivity::class.java)
        onView(withId(R.id.text_flag_secure_label)).check(matches(isDisplayed()))
    }

    @Test
    fun flagSecureIsSet() {
        ActivityScenario.launch(FlagSecureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }
}
