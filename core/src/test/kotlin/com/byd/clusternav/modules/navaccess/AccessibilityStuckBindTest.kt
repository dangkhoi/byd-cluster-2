package com.byd.clusternav.modules.navaccess

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Nhận biết trạng thái **AMS KẸT** của accessibility service + việc cần làm tương ứng (v1.40).
 *
 * Bối cảnh đo trên xe owner 2026-09-11 (owner báo "mất kết nối phím thoại"): service ENABLED, `Bound services`
 * KHÔNG có ClusterNav, `Binding services` CÓ ClusterNav, kèm `ConnectionRecord … FGSA DEAD` của các tiến trình
 * cũ. Thứ tự đã thử: toggle setting → `bound=false`; gỡ HẲN component khỏi `enabled_accessibility_services` →
 * `Binding services` VẪN còn nó; `am force-stop` → `Binding services:{}` rồi bind lại được. Kết luận: ở trạng
 * thái này ghi setting là vô ích, phải khởi động lại tiến trình.
 *
 * Fixture dưới đây là **dump thật** copy từ xe (rút gọn phần không liên quan), nên test khoá đúng định dạng
 * ROM DiLink 3.0 chứ không phải một chuỗi tự bịa.
 */
class AccessibilityStuckBindTest {

    private val stuckDump = """
        Accessibility enabled: true
             Bound services:{Service[label=.custom.StatusBarAcces…, feedbackType[FEEDBACK_SPOKEN, FEEDBACK_HAPTIC], capabilities=43, eventTypes=[TYPE_WINDOW_STATE_CHANGED], notificationTimeout=100]}
             Enabled services:{{com.byd.vrassistant.xf/com.iflytek.autofly.access.service.AccessibilityServices}, {com.byd.clusternav2/com.byd.clusternav.modules.navaccess.NavAccessibilityService}, {com.android.systemui/com.android.systemui.custom.StatusBarAccessibilityService}}
             Binding services:{{com.byd.clusternav2/com.byd.clusternav.modules.navaccess.NavAccessibilityService}}]
    """.trimIndent()

    private val healthyDump = """
        Accessibility enabled: true
             Bound services:{Service[label=.custom.StatusBarAcces…, capabilities=43, notificationTimeout=100], 
                             Service[label=ClusterNav — booster đọ…, feedbackType[FEEDBACK_GENERIC], capabilities=9, notificationTimeout=500]}
             Enabled services:{{com.byd.vrassistant.xf/com.iflytek.autofly.access.service.AccessibilityServices}, {com.byd.clusternav2/com.byd.clusternav.modules.navaccess.NavAccessibilityService}, {com.android.systemui/com.android.systemui.custom.StatusBarAccessibilityService}}
             Binding services:{}]
    """.trimIndent()

    /** Sau reboot: enabled nhưng chưa bind, và KHÔNG kẹt (mục Binding rỗng) — đây là ca toggle chữa được. */
    private val notBoundNotStuckDump = """
        Accessibility enabled: true
             Bound services:{Service[label=.custom.StatusBarAcces…, capabilities=43]}
             Enabled services:{{com.byd.clusternav2/com.byd.clusternav.modules.navaccess.NavAccessibilityService}}
             Binding services:{}]
    """.trimIndent()

    // ───────────────────────────── isBindingStuck ─────────────────────────────

    @Test
    fun `dump THAT tu xe - nhan ra dang KET o Binding services`() {
        assertTrue(AccessibilityRebind.isBindingStuck(stuckDump))
        assertFalse(AccessibilityRebind.isClusterNavBound(stuckDump), "cùng dump đó thì KHÔNG được coi là bound")
    }

    @Test
    fun `dump lanh - Binding rong thi khong ket`() {
        assertFalse(AccessibilityRebind.isBindingStuck(healthyDump))
        assertTrue(AccessibilityRebind.isClusterNavBound(healthyDump))
    }

    @Test
    fun `muc Enabled co ClusterNav KHONG bi nham la ket`() {
        // Cả 3 dump đều liệt kê ClusterNav ở "Enabled services"; chỉ dump kẹt mới có nó ở "Binding services".
        assertFalse(AccessibilityRebind.isBindingStuck(notBoundNotStuckDump), "Enabled ≠ Binding — phải cắt đúng section")
    }

    @Test
    fun `dump khong doc duoc - KHONG ket luan la ket (fail-safe nguoc voi bound)`() {
        // Khởi động lại tiến trình là hành động nặng ⇒ không được kích hoạt vì một lần đọc lỗi.
        listOf(null, "", "   ", "Accessibility enabled: true").forEach { d ->
            assertFalse(AccessibilityRebind.isBindingStuck(d), "dump '$d' phải cho ra KHÔNG kẹt")
        }
        // Ngược lại, isClusterNavBound fail-safe = true (không toggle mù trên dump lỗi).
        assertTrue(AccessibilityRebind.isClusterNavBound(null))
    }

    // ───────────────────────────── healStep ─────────────────────────────

    @Test
    fun `da bound thi khong lam gi`() {
        assertEquals(AccessibilityRebind.RebindStep.NONE, AccessibilityRebind.healStep(bound = true, bindingStuck = false))
        assertEquals(
            AccessibilityRebind.RebindStep.NONE,
            AccessibilityRebind.healStep(bound = true, bindingStuck = true),
            "đã bound thì trạng thái Binding cũ không còn nghĩa gì — tuyệt đối không toggle/không restart",
        )
    }

    @Test
    fun `chua bound va KHONG ket thi TOGGLE (duong proven 2026-08-14)`() {
        assertEquals(AccessibilityRebind.RebindStep.TOGGLE, AccessibilityRebind.healStep(bound = false, bindingStuck = false))
    }

    @Test
    fun `chua bound va KET thi RESTART_PROCESS (toggle vo ich - do tren xe)`() {
        assertEquals(
            AccessibilityRebind.RebindStep.RESTART_PROCESS,
            AccessibilityRebind.healStep(bound = false, bindingStuck = true),
        )
    }

    @Test
    fun `chuoi quyet dinh doc TRUC TIEP tu 3 dump that`() {
        fun stepFor(dump: String) = AccessibilityRebind.healStep(
            AccessibilityRebind.isClusterNavBound(dump),
            AccessibilityRebind.isBindingStuck(dump),
        )
        assertEquals(AccessibilityRebind.RebindStep.NONE, stepFor(healthyDump))
        assertEquals(AccessibilityRebind.RebindStep.TOGGLE, stepFor(notBoundNotStuckDump))
        assertEquals(AccessibilityRebind.RebindStep.RESTART_PROCESS, stepFor(stuckDump))
    }

    // ───────────────────────────── component identity ─────────────────────────────

    @Test
    fun `component dung applicationId truyen vao, KHONG hardcode package cu`() {
        assertEquals(
            "com.byd.clusternav2/com.byd.clusternav.modules.navaccess.NavAccessibilityService",
            AccessibilityRebind.component("com.byd.clusternav2"),
        )
        // Bẫy đã gỡ: hằng số cũ ghi "com.byd.clusternav/…" (app 1.x) trong khi bản 2.0 là com.byd.clusternav2.
        assertFalse(
            AccessibilityRebind.ACC_SERVICE_CLASS.startsWith("com.byd.clusternav2"),
            "ACC_SERVICE_CLASS là tên CLASS (package Kotlin), không được chứa applicationId",
        )
    }

    @Test
    fun `toggle writes go dung component cua ban build 2 dot 0`() {
        val acc = AccessibilityRebind.component("com.byd.clusternav2")
        val other = "com.byd.vrassistant.xf/com.iflytek.autofly.access.service.AccessibilityServices"
        val w = AccessibilityRebind.accessibilityRebindWrites("$other:$acc", boundContainsClusterNav = false, component = acc)
        assertEquals(3, w.size)
        assertTrue(w[0].contains("\"$other\""), "lệnh remove phải GIỮ service của OEM và bỏ ĐÚNG component của mình")
        assertFalse(w[0].contains(acc), "component của mình phải bị gỡ ở bước remove — nếu không thì toggle không toggle gì cả")
        assertTrue(w[1].contains("$other:$acc"), "bước re-add trả lại đủ, component mình ở cuối")
    }
}
