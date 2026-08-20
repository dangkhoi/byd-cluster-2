package com.byd.clusternav

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract lock for B3.7 (multi-app overlay contamination) in the Android accessibility service — the parts
 * that touch AccessibilityEvent/AccessibilityWindowInfo and cannot run on the stubbed JVM android.jar, so we
 * assert the gating wiring by scanning source (same technique as [ScreenCaptureNavSourceContractTest]). The
 * pure DECISION is covered off-car by `ForegroundWindowFilterTest`.
 */
class NavForegroundGateContractTest {

    private val access =
        SourceRoots.text("src/main/java/com/byd/clusternav/modules/navaccess/NavAccessibilityService.kt")

    @Test
    fun `foreground + bounds publish is gated by the real-foreground-app check`() {
        // The publish must sit INSIDE the isEventFromForegroundApp(...) guard, not be called unconditionally.
        val guard = access.indexOf("if (isEventFromForegroundApp(event, pkg)) {")
        assertTrue(guard >= 0, "B3.7: publish must be gated by isEventFromForegroundApp(event, pkg)")
        val publish = access.indexOf("CaptureForegroundSource.publish(pkg, b3Now)")
        val bounds = access.indexOf("maybePublishCaptureBounds(event, pkg, b3Now)")
        assertTrue(publish > guard, "foreground publish is inside the guard")
        assertTrue(bounds > guard, "bounds publish is inside the guard")
    }

    @Test
    fun `decision is delegated to the pure ForegroundWindowFilter`() {
        assertTrue(
            access.contains("ForegroundWindowFilter.shouldPublishForeground("),
            "B3.7: Android side must delegate to the pure filter",
        )
    }

    @Test
    fun `window type active focused are read from the accessibility window info`() {
        assertTrue(access.contains("event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED"), "state-change signal")
        assertTrue(access.contains("node?.window"), "reads window info when available")
        assertTrue(access.contains("win.type") && access.contains("win.isActive") && access.contains("win.isFocused"), "reads type/active/focused")
        // Degrade-safe: window info is wrapped so a null / unavailable window never crashes the callback.
        assertTrue(access.contains("ForegroundWindowFilter.TYPE_UNKNOWN"), "degrades to UNKNOWN when window info absent")
    }
}
