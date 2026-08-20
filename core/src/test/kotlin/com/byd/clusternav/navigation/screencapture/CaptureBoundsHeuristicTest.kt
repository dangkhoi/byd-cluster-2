package com.byd.clusternav.navigation.screencapture

import com.byd.clusternav.navigation.screencapture.CaptureBoundsHeuristic.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Pure off-car lock for the arrow/camera a11y-node picker (node identification itself = VERIFY-ON-CAR). */
class CaptureBoundsHeuristicTest {

    private val region = CropRect(0, 0, 1920, 720)

    @Test
    fun `arrow keyword beats generic panel`() {
        val arrow = CropRect(30, 220, 200, 300)
        val picked = CaptureBoundsHeuristic.pick(
            CaptureTarget.ARROW,
            listOf(
                Candidate("android.view.View", "Guidance panel", CropRect(0, 0, 800, 400)),
                Candidate("android.widget.ImageView", "Turn right", arrow),
            ),
            region,
        )
        assertEquals(arrow, picked)
    }

    @Test
    fun `camera keyword picked for VietMap target`() {
        val cam = CropRect(1500, 100, 1620, 220)
        val picked = CaptureBoundsHeuristic.pick(
            CaptureTarget.CAMERA,
            listOf(
                Candidate("android.widget.TextView", "80", CropRect(1000, 100, 1100, 160)),
                Candidate("android.widget.ImageView", "Speed camera ahead", cam),
            ),
            region,
        )
        assertEquals(cam, picked)
    }

    @Test
    fun `too-small node rejected`() {
        val picked = CaptureBoundsHeuristic.pick(
            CaptureTarget.ARROW,
            listOf(Candidate("android.widget.ImageView", "turn", CropRect(0, 0, 4, 4))),
            region,
        )
        assertNull(picked)
    }

    @Test
    fun `full-screen panel rejected as too big`() {
        val picked = CaptureBoundsHeuristic.pick(
            CaptureTarget.ARROW,
            listOf(Candidate("android.view.View", "turn arrow", CropRect(0, 0, 1920, 720))),
            region,
        )
        assertNull(picked)
    }

    @Test
    fun `image fallback when no keyword`() {
        val icon = CropRect(40, 40, 120, 120)
        val picked = CaptureBoundsHeuristic.pick(
            CaptureTarget.ARROW,
            listOf(
                Candidate("android.widget.TextView", "500 m", CropRect(200, 40, 360, 90)),
                Candidate("android.widget.ImageView", "", icon),
            ),
            region,
        )
        assertEquals(icon, picked)
    }

    @Test
    fun `candidate outside region excluded`() {
        val rightRegion = CropRect(960, 0, 1920, 720)
        val picked = CaptureBoundsHeuristic.pick(
            CaptureTarget.ARROW,
            listOf(Candidate("android.widget.ImageView", "turn left", CropRect(30, 220, 200, 300))),
            rightRegion,
        )
        assertNull(picked)
    }

    @Test
    fun `empty candidates to null`() {
        assertNull(CaptureBoundsHeuristic.pick(CaptureTarget.ARROW, emptyList(), region))
    }
}
