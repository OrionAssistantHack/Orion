package com.orion

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenCaptureServiceTest {

    @Test
    fun `isFrameSecure returns true for fully black bitmap`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        assertTrue(ScreenCaptureService.isFrameSecure(bmp))
    }

    @Test
    fun `isFrameSecure returns false for white bitmap`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertFalse(ScreenCaptureService.isFrameSecure(bmp))
    }

    @Test
    fun `isFrameSecure returns false for blue bitmap`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        assertFalse(ScreenCaptureService.isFrameSecure(bmp))
    }
}
