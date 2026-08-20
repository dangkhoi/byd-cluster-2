package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit test THUẦN cho [NavOutputDecision] (T4, spec `b3-full-nav-capture` §R3/§R4/§R5) — khoá logic
 * "cho mức tươi → bắn kênh nào / khi nào clear" + hai hàm map (lane-arrow code, camera icon code) off-car.
 *
 * Kịch bản theo task T4: arrow-only · lane+arrow · camera · all-stale→clear · keep-alive (re-assert khi tươi,
 * clear khi hết tươi). CỨ BẮN — không gate chéo kênh (R3a/OQ4).
 */
class NavOutputDecisionTest {

    @Test fun `arrow-only — chỉ bắn arrow, không clear`() {
        val p = NavOutputDecision.decide(arrowFresh = true, laneFresh = false, cameraFresh = false)
        assertTrue(p.pushArrow)
        assertFalse(p.pushLane)
        assertFalse(p.pushCamera)
        assertFalse(p.clear)
        assertTrue(p.anyPush)
    }

    @Test fun `lane+arrow — bắn CẢ arrow lẫn lane (độc lập, cứ bắn), không clear`() {
        val p = NavOutputDecision.decide(arrowFresh = true, laneFresh = true, cameraFresh = false)
        assertTrue(p.pushArrow)
        assertTrue(p.pushLane)
        assertFalse(p.pushCamera)
        assertFalse(p.clear)
    }

    @Test fun `camera — chỉ bắn camera (không cần arrow tươi, không gate chéo)`() {
        val p = NavOutputDecision.decide(arrowFresh = false, laneFresh = false, cameraFresh = true)
        assertFalse(p.pushArrow)
        assertFalse(p.pushLane)
        assertTrue(p.pushCamera)
        assertFalse(p.clear)
    }

    @Test fun `cả ba kênh tươi — bắn hết (cứ bắn đủ field)`() {
        val p = NavOutputDecision.decide(arrowFresh = true, laneFresh = true, cameraFresh = true)
        assertTrue(p.pushArrow && p.pushLane && p.pushCamera)
        assertFalse(p.clear)
    }

    @Test fun `all-stale → clear (không kênh nào tươi ⇒ nhả frame)`() {
        val p = NavOutputDecision.decide(arrowFresh = false, laneFresh = false, cameraFresh = false)
        assertFalse(p.anyPush)
        assertTrue(p.clear)
    }

    @Test fun `keep-alive — còn tươi thì mỗi tick vẫn ra plan bắn (re-assert), hết tươi thì clear`() {
        // Nguồn còn tươi qua nhiều tick liên tiếp ⇒ decide luôn trả "bắn" ⇒ owner re-assert nội dung mỗi nhịp.
        repeat(4) {
            val p = NavOutputDecision.decide(arrowFresh = true, laneFresh = true, cameraFresh = false)
            assertTrue(p.anyPush, "còn tươi ⇒ vẫn bắn (keep-alive re-assert)")
            assertFalse(p.clear)
        }
        // Nguồn hết tươi ⇒ chuyển sang clear đúng một lần (owner tự chống clear lặp bằng hasFrame).
        val stale = NavOutputDecision.decide(arrowFresh = false, laneFresh = false, cameraFresh = false)
        assertFalse(stale.anyPush)
        assertTrue(stale.clear)
    }

    @Test fun `laneArrowCode — dùng AMAP NEW_ICON của mũi tên chính, rỗng = 0`() {
        assertEquals(0, NavOutputDecision.laneArrowCode(emptyList()))
        assertEquals(2, NavOutputDecision.laneArrowCode(listOf(Maneuver.TURN_LEFT)))     // AMAP 2
        assertEquals(3, NavOutputDecision.laneArrowCode(listOf(Maneuver.TURN_RIGHT)))    // AMAP 3
        assertEquals(9, NavOutputDecision.laneArrowCode(listOf(Maneuver.STRAIGHT)))      // AMAP 9
        // Làn nhiều hướng (thẳng + phải): lấy mũi tên CHÍNH (đầu) = thẳng → 9.
        assertEquals(9, NavOutputDecision.laneArrowCode(listOf(Maneuver.STRAIGHT, Maneuver.TURN_RIGHT)))
    }

    @Test fun `laneArrowCode khớp Maneuver_toAmapIcon của phần tử đầu (single source)`() {
        for (m in Maneuver.values()) {
            assertEquals(m.toAmapIcon(), NavOutputDecision.laneArrowCode(listOf(m)), "$m")
        }
    }

    @Test fun `cameraIconCode — 1 khi có camera, 0 khi không`() {
        assertEquals(1, NavOutputDecision.cameraIconCode(hasCamera = true))
        assertEquals(0, NavOutputDecision.cameraIconCode(hasCamera = false))
    }
}
