package com.byd.clusternav.comfort

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Device-free unit tests for [SeatComfort] (pure :core model). Locks the RE feature-id table, the
 * user-level → HAL value map, seat-count-by-model, and the Han detector.
 *
 * Red-green: e.g. flipping `HAL_VALUE` to `intArrayOf(0,1,2)` or dropping the +8/row rear pattern makes
 * the relevant assertion RED — the table below is the single source of truth these guard.
 */
class SeatComfortTest {

    @Test fun `feature-id table matches RE mapping COOL and HEAT per seat`() {
        // Front-left / driver — proven on owner car.
        assertEquals(0x43101010, SeatComfort.featureId(0, SeatComfort.SeatMode.COOL))
        assertEquals(0x43101014, SeatComfort.featureId(0, SeatComfort.SeatMode.HEAT))
        // Front-right / passenger — proven on owner car.
        assertEquals(0x43101018, SeatComfort.featureId(1, SeatComfort.SeatMode.COOL))
        assertEquals(0x4310101C, SeatComfort.featureId(1, SeatComfort.SeatMode.HEAT))
        // Rear-left (Han) — EXTRAPOLATED +8 per row.
        assertEquals(0x43101020, SeatComfort.featureId(2, SeatComfort.SeatMode.COOL))
        assertEquals(0x43101024, SeatComfort.featureId(2, SeatComfort.SeatMode.HEAT))
        // Rear-right (Han) — EXTRAPOLATED +8 per row.
        assertEquals(0x43101028, SeatComfort.featureId(3, SeatComfort.SeatMode.COOL))
        assertEquals(0x4310102C, SeatComfort.featureId(3, SeatComfort.SeatMode.HEAT))
    }

    @Test fun `feature-id pattern is +8 per row and +4 cool to heat`() {
        for (i in SeatComfort.SEATS.indices) {
            val cool = SeatComfort.featureId(i, SeatComfort.SeatMode.COOL)
            val heat = SeatComfort.featureId(i, SeatComfort.SeatMode.HEAT)
            assertEquals(4, heat - cool, "seat $i: heat must be cool+4")
            assertEquals(0x43101010 + i * 8, cool, "seat $i: cool must be 0x43101010 + i*8")
        }
    }

    @Test fun `halValue maps user level to HAL value OFF-0 L1-2 L2-3`() {
        assertEquals(0, SeatComfort.halValue(0))   // OFF
        assertEquals(2, SeatComfort.halValue(1))   // Mức 1
        assertEquals(3, SeatComfort.halValue(2))   // Mức 2
        // Degrade-safe clamp (never throws for a corrupt stored level).
        assertEquals(0, SeatComfort.halValue(-1))
        assertEquals(3, SeatComfort.halValue(99))
        // HAL_VALUE array itself is the adjustable source of truth.
        assertTrue(SeatComfort.HAL_VALUE.contentEquals(intArrayOf(0, 2, 3)))
    }

    @Test fun `seatsForModel gives 2 for Seal and 4 for Han`() {
        assertEquals(listOf(0, 1), SeatComfort.seatsForModel(isHan = false))
        assertEquals(listOf(0, 1, 2, 3), SeatComfort.seatsForModel(isHan = true))
        assertEquals(2, SeatComfort.seatCountForModel(false))
        assertEquals(4, SeatComfort.seatCountForModel(true))
    }

    @Test fun `isHanModel is case-insensitive contains han and safe on null`() {
        assertTrue(SeatComfort.isHanModel("Han"))
        assertTrue(SeatComfort.isHanModel("BYD HAN EV"))
        assertTrue(SeatComfort.isHanModel("byd_han_dmi"))
        assertFalse(SeatComfort.isHanModel("Seal"))
        assertFalse(SeatComfort.isHanModel("Sealion 6"))
        assertFalse(SeatComfort.isHanModel(""))
        assertFalse(SeatComfort.isHanModel(null))   // unknown → Seal (2 seats), the safe default
    }
}
