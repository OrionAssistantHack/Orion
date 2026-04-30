package com.orion.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.orion.core.ExecutionResult
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class MockAutomationExecutorTest {

    private val fakeNode: AccessibilityNodeInfo = mock()

    @Test
    fun `tapNode records call and returns configured result`() {
        val executor = MockAutomationExecutor(tapResult = ExecutionResult(success = true))
        val result = executor.tapNode(fakeNode)
        assertTrue(result.success)
        assertEquals(1, executor.tapNodeCalls.size)
        assertSame(fakeNode, executor.tapNodeCalls[0])
    }

    @Test
    fun `dispatchTap records coordinates`() {
        val executor = MockAutomationExecutor()
        executor.dispatchTap(100f, 200f)
        assertEquals(1, executor.dispatchTapCalls.size)
        assertEquals(100f, executor.dispatchTapCalls[0].first)
        assertEquals(200f, executor.dispatchTapCalls[0].second)
    }

    @Test
    fun `typeText records node and text`() {
        val executor = MockAutomationExecutor()
        executor.typeText(fakeNode, "hello")
        assertEquals(1, executor.typeTextCalls.size)
        assertEquals("hello", executor.typeTextCalls[0].second)
    }

    @Test
    fun `isScreenSecure reflects configured value`() {
        assertTrue(MockAutomationExecutor(secureScreen = true).isScreenSecure())
        assertFalse(MockAutomationExecutor(secureScreen = false).isScreenSecure())
    }

    @Test
    fun `failed tap result propagates`() {
        val executor = MockAutomationExecutor(tapResult = ExecutionResult(success = false, errorCode = "MISS"))
        val result = executor.tapNode(fakeNode)
        assertFalse(result.success)
        assertEquals("MISS", result.errorCode)
    }
}
