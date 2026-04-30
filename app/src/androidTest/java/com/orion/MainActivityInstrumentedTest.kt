package com.orion

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @Test
    fun appNameDisplayed() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withText("Orion Assistant")).check(matches(isDisplayed()))
    }

    @Test
    fun goalHintVisible() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withHint("What do you want to do?")).check(matches(isDisplayed()))
    }

    @Test
    fun appSelectorButtonsPresent() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withText("Uber")).check(matches(isDisplayed()))
        onView(withText("Lyft")).check(matches(isDisplayed()))
        onView(withText("AI Gallery")).check(matches(isDisplayed()))
    }

    @Test
    fun startButtonInitiallyDisabled() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.btn_start)).check(matches(isNotEnabled()))
    }

    @Test
    fun stopButtonInitiallyDisabled() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.btn_stop)).check(matches(isNotEnabled()))
    }
}
