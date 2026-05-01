package com.orion.ui

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OrionPipOverlayTest {

    @Before
    fun setUp() {
        OrionPipOverlay.dismiss()
    }

    @Test
    fun isShowing_falseByDefault() {
        assertFalse(OrionPipOverlay.isShowing)
    }

    @Test
    fun dismiss_whenNothingShown_doesNotCrash() {
        OrionPipOverlay.dismiss()
        assertFalse(OrionPipOverlay.isShowing)
    }

    @Test
    fun dismiss_setsIsShowingFalse() {
        OrionPipOverlay.dismiss()
        assertFalse(OrionPipOverlay.isShowing)
    }
}
