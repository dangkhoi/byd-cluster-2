package com.byd.clusternav

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Off-car unit test for the [VietMapAutostart] re-entrancy + cooldown gate (B2, on-car 2026-09-06).
 *
 * The bug: with the VietMap bubble ON, every `MainActivity.onCreate` (open / theme-or-language recreate / boot
 * auto-open) ran "launch VietMap → sleep 1.5s → relaunch ClusterNav" with NO dedup → a foreground flash loop.
 * This locks the PURE gate that stops it: one run in-flight at a time, plus a cooldown between runs so a burst
 * of onCreate/recreate/boot triggers only launches once.
 *
 * Pure (no Android): drives [VietMapAutostart.outsideCooldown] + `tryBeginRun`/`finishRun` with explicit
 * clocks. Red-green: dropping the in-flight CAS makes the "second claim while in-flight" test RED; dropping the
 * cooldown check makes the "claim within window after finish" test RED.
 */
class VietMapAutostartGateTest {

    @BeforeEach fun setUp() { VietMapAutostart.resetGateForTest() }
    @AfterEach fun tearDown() { VietMapAutostart.resetGateForTest() }

    private val cd = VietMapAutostart.COOLDOWN_MS

    @Test fun `outsideCooldown is true on the first ever run regardless of clock`() {
        assertTrue(VietMapAutostart.outsideCooldown(nowMs = 0L, lastRunAtMs = 0L))
        assertTrue(VietMapAutostart.outsideCooldown(nowMs = 123_456L, lastRunAtMs = 0L))
    }

    @Test fun `outsideCooldown is false inside the window and true at-or-after the boundary`() {
        val last = 10_000L
        assertFalse(VietMapAutostart.outsideCooldown(last + 1, last), "just after a run")
        assertFalse(VietMapAutostart.outsideCooldown(last + cd - 1, last), "1ms before boundary")
        assertTrue(VietMapAutostart.outsideCooldown(last + cd, last), "exactly at boundary (>=)")
        assertTrue(VietMapAutostart.outsideCooldown(last + cd + 1, last), "after boundary")
    }

    @Test fun `first claim succeeds and a re-entrant claim while in-flight is refused`() {
        assertTrue(VietMapAutostart.tryBeginRun(nowMs = 1_000L), "first claim proceeds")
        assertFalse(VietMapAutostart.tryBeginRun(nowMs = 1_000L), "re-entrant claim while in-flight is refused")
        VietMapAutostart.finishRun()
    }

    @Test fun `after finish a claim within cooldown is refused but outside it succeeds`() {
        assertTrue(VietMapAutostart.tryBeginRun(nowMs = 1_000L))
        VietMapAutostart.finishRun()
        assertFalse(VietMapAutostart.tryBeginRun(nowMs = 1_000L + cd - 1), "within cooldown after finish → refused")
        assertTrue(VietMapAutostart.tryBeginRun(nowMs = 1_000L + cd), "outside cooldown → allowed")
        VietMapAutostart.finishRun()
    }

    @Test fun `finishRun releases the slot so the next allowed run can claim`() {
        assertTrue(VietMapAutostart.tryBeginRun(nowMs = 1_000L))
        VietMapAutostart.finishRun()
        assertTrue(VietMapAutostart.tryBeginRun(nowMs = 1_000L + cd * 10), "far outside cooldown + not in-flight")
        VietMapAutostart.finishRun()
    }
}
