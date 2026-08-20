package com.byd.clusternav.navigation.screencapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure off-car lock for the screen-capture holders (publish -> snapshot -> freshness -> clear). */
class ScreenCaptureHoldersTest {

    @Test
    fun `bounds source publishes and clears`() {
        CaptureBoundsSource.clear()
        assertNull(CaptureBoundsSource.snapshot())
        CaptureBoundsSource.publish(10, 20, 210, 120, now = 1000L)
        val snap = CaptureBoundsSource.snapshot()
        assertNotNull(snap)
        assertEquals(10, snap!!.rect.left)
        assertEquals(120, snap.rect.bottom)
        assertEquals(1000L, snap.capturedAtMs)
        CaptureBoundsSource.clear()
        assertNull(CaptureBoundsSource.snapshot())
    }

    @Test
    fun `bounds source rejects empty rect`() {
        CaptureBoundsSource.clear()
        CaptureBoundsSource.publish(10, 10, 10, 10, now = 5L)
        assertNull(CaptureBoundsSource.snapshot())
    }

    @Test
    fun `foreground source freshness window`() {
        CaptureForegroundSource.clear()
        assertFalse(CaptureForegroundSource.isFresh(0L))
        CaptureForegroundSource.publish("com.waze", now = 1000L)
        assertEquals("com.waze", CaptureForegroundSource.pkg)
        assertTrue(CaptureForegroundSource.isFresh(1000L + CaptureForegroundSource.FRESH_MS))
        assertFalse(CaptureForegroundSource.isFresh(1000L + CaptureForegroundSource.FRESH_MS + 1))
        CaptureForegroundSource.clear()
        assertNull(CaptureForegroundSource.pkg)
    }

    @Test
    fun `signal stores arrow and camera with freshness`() {
        ScreenCaptureSignal.clear()
        assertFalse(ScreenCaptureSignal.arrowFresh(0L))
        ScreenCaptureSignal.publishArrow("com.waze", maneuver = null, amap = 2, now = 100L)
        assertEquals("com.waze", ScreenCaptureSignal.arrowPkg)
        assertEquals(2, ScreenCaptureSignal.arrowAmap)
        assertTrue(ScreenCaptureSignal.arrowFresh(100L + ScreenCaptureSignal.STALE_MS))
        assertFalse(ScreenCaptureSignal.arrowFresh(100L + ScreenCaptureSignal.STALE_MS + 1))

        ScreenCaptureSignal.publishCamera("vn.vietmap.live", CameraMatch(hasCamera = true, score = 0.9f, templateName = "fixed"), now = 200L)
        assertEquals("vn.vietmap.live", ScreenCaptureSignal.cameraPkg)
        assertTrue(ScreenCaptureSignal.cameraMatch!!.hasCamera)
        ScreenCaptureSignal.clear()
        assertNull(ScreenCaptureSignal.arrowPkg)
        assertNull(ScreenCaptureSignal.cameraMatch)
    }
}
