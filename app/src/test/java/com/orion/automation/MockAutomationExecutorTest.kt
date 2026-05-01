package com.orion.automation

import android.graphics.Bitmap
import com.orion.core.ExecutionResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MockAutomationExecutorTest {

    @Test
    fun `tapNode sets lastTappedText and returns success`() {
        val executor = MockAutomationExecutor()
        val result = executor.tapNode("Search")
        assertTrue(result.success)
        assertEquals("Search", executor.lastTappedText)
    }

    @Test
    fun `dispatchTap sets lastTapX and lastTapY`() {
        val executor = MockAutomationExecutor()
        executor.dispatchTap(100f, 200f)
        assertEquals(100f, executor.lastTapX)
        assertEquals(200f, executor.lastTapY)
    }

    @Test
    fun `isScreenSecure returns secureResult and stores bitmap`() {
        val executor = MockAutomationExecutor(secureResult = true)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertTrue(executor.isScreenSecure(bitmap))
        assertSame(bitmap, executor.lastBitmap)
    }
}
