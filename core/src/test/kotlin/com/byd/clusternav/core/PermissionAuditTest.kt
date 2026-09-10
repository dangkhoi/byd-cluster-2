package com.byd.clusternav.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bộ kiểm-tra-quyền (spec `docs/specs/permission-health-audit.html`). Khoá ba thứ:
 *
 *  1. **Cần gì theo tính năng** — tính năng TẮT thì quyền của nó KHÔNG được đòi (không quấy owner vì thứ họ
 *     không dùng); tính năng BẬT thì không được thiếu mục nào.
 *  2. **Đủ ⇒ không mở phiên, không phát lệnh** — cổng [PermissionAudit.needsShellSession]. Đây là yêu cầu
 *     owner ("đọc trước, nếu đã cấp thì không cấp lại"), nên nó phải có test canh.
 *  3. **Parser output shell** — appop mode / doze whitelist / secure list phẳng / secure `pkg/class`. Toàn bộ
 *     đọc ra "chưa cấp" khi rỗng-hoặc-`null`, vì đó chính là ca on-car v1.38 (appop của bản mod VietMap bị
 *     xoá khi cài lại) mà đường cũ tưởng là "đã cấp rồi".
 */
class PermissionAuditTest {

    // ───────────────────────────── 1. requiredFor: cần gì theo tính năng ─────────────────────────────

    @Test
    fun `tat het tinh nang - khong doi quyen nao`() {
        assertEquals(emptySet<Grant>(), PermissionAudit.requiredFor(AuditFeatures()))
    }

    @Test
    fun `Nav+HUD can notification + a11y + doze cua chinh app`() {
        val r = PermissionAudit.requiredFor(AuditFeatures(nav = true))
        assertEquals(setOf(Grant.NOTIF_LISTENER, Grant.A11Y_SERVICE, Grant.DOZE_SELF), r)
    }

    @Test
    fun `voice-key can a11y NHUNG khong can notification (hai tinh nang RIENG)`() {
        val r = PermissionAudit.requiredFor(AuditFeatures(voiceKey = true))
        assertTrue(Grant.A11Y_SERVICE in r)
        assertFalse(Grant.NOTIF_LISTENER in r, "phím-thoại không đọc thông báo")
    }

    @Test
    fun `binding Gemini can tro ly he thong`() {
        val r = PermissionAudit.requiredFor(AuditFeatures(voiceKey = true, geminiBinding = true))
        assertTrue(Grant.ASSISTANT in r)
        assertFalse(Grant.ASSISTANT in PermissionAudit.requiredFor(AuditFeatures(voiceKey = true)))
    }

    @Test
    fun `bong VietMap can quyen cua GOI VIETMAP (list + appop) chu khong chi cua app minh`() {
        val r = PermissionAudit.requiredFor(AuditFeatures(vmBubble = true))
        assertTrue(Grant.VM_FLOAT_LIST in r, "bóng do chính VietMap vẽ ⇒ VietMap phải có trong byd_float_app_list")
        assertTrue(Grant.VM_OVERLAY_OP in r, "và phải có appop SYSTEM_ALERT_WINDOW")
        assertTrue(Grant.DOZE_VIETMAP in r)
    }

    @Test
    fun `badge - Cast can overlay cua chinh app (appop + float list)`() {
        for (f in listOf(AuditFeatures(badge = true), AuditFeatures(cast = true))) {
            val r = PermissionAudit.requiredFor(f)
            assertTrue(Grant.SELF_OVERLAY in r, "$f phải cần appop overlay của app")
            assertTrue(Grant.SELF_FLOAT_LIST in r, "$f phải cần tên app trong byd_float_app_list")
        }
    }

    @Test
    fun `bong VietMap TAT thi khong bao gio doi quyen OVERLAY cua goi VietMap`() {
        val r = PermissionAudit.requiredFor(
            AuditFeatures(nav = true, voiceKey = true, cast = true, geminiBinding = true),
        )
        assertFalse(Grant.VM_FLOAT_LIST in r)
        assertFalse(Grant.VM_OVERLAY_OP in r, "không bật bóng ⇒ không cần quyền VẼ của VietMap")
    }

    @Test
    fun `Cast bat thi VietMap phai duoc mien doze (spec 4-2 muc 8)`() {
        // Đường Cast (`SimpleCastCoordinator.openProjection`) chiếu VietMap lên cụm nên nó vẫn phải sống ở nền —
        // chính vì thế Cast đã whitelist doze cho VietMap từ trước bằng CỜ MỘT-LẦN `doze_whitelist_applied`.
        // Cổng này là chỗ đường ĐỌC-TRƯỚC phủ lại đúng ca đó (R1: sự thật đọc từ thiết bị, không tin cờ).
        assertTrue(Grant.DOZE_VIETMAP in PermissionAudit.requiredFor(AuditFeatures(cast = true)))
        assertTrue(Grant.DOZE_VIETMAP in PermissionAudit.requiredFor(AuditFeatures(badge = true)))
        assertTrue(Grant.DOZE_VIETMAP in PermissionAudit.requiredFor(AuditFeatures(vmBubble = true)))
        assertFalse(
            Grant.DOZE_VIETMAP in PermissionAudit.requiredFor(AuditFeatures(nav = true, voiceKey = true)),
            "chỉ Nav+HUD / phím-thoại thì không dính VietMap",
        )
    }

    @Test
    fun `VietMap CHUA CAI thi khong doi quyen nao cua no (khong the va - khong duoc quay owner)`() {
        val r = PermissionAudit.requiredFor(
            AuditFeatures(badge = true, vmBubble = true, cast = true, vmInstalled = false),
        )
        // Gói không tồn tại ⇒ `appops set` / `deviceidle +` / có tên trong byd_float_app_list đều vá KHÔNG được
        // ⇒ đòi = popup vĩnh viễn vô nghĩa. Cờ bóng/badge vẫn BẬT sau khi owner gỡ VietMap nên ca này thật.
        assertFalse(Grant.VM_FLOAT_LIST in r)
        assertFalse(Grant.VM_OVERLAY_OP in r)
        assertFalse(Grant.DOZE_VIETMAP in r)
        // Nhưng quyền của CHÍNH app thì vẫn cần (badge/Cast vẫn vẽ được lên cụm).
        assertTrue(Grant.SELF_OVERLAY in r)
        assertTrue(Grant.SELF_FLOAT_LIST in r)
        assertTrue(Grant.DOZE_SELF in r)
    }

    @Test
    fun `bat het - can du 9 muc`() {
        val r = PermissionAudit.requiredFor(
            AuditFeatures(nav = true, voiceKey = true, badge = true, vmBubble = true, cast = true, geminiBinding = true),
        )
        assertEquals(Grant.entries.toSet(), r)
    }

    // ───────────────── 2. Đủ ⇒ KHÔNG mở phiên dadb (owner: "đã cấp thì không cấp lại") ─────────────────

    @Test
    fun `chi con muc doc in-process va DA DU - KHONG mo phien dadb`() {
        val required = setOf(Grant.NOTIF_LISTENER, Grant.A11Y_SERVICE)
        assertFalse(PermissionAudit.needsShellSession(required, okInProcess = required))
    }

    @Test
    fun `muc doc in-process THIEU - phai mo phien dadb de va`() {
        val required = setOf(Grant.NOTIF_LISTENER, Grant.A11Y_SERVICE)
        assertTrue(PermissionAudit.needsShellSession(required, okInProcess = setOf(Grant.A11Y_SERVICE)))
    }

    @Test
    fun `co muc chi doc duoc bang shell - luon phai mo phien`() {
        // float list / appop VietMap / doze / trợ lý: không có API in-process nào đọc được ⇒ phải shell.
        for (g in Grant.entries - PermissionAudit.IN_PROCESS_READABLE) {
            assertTrue(
                PermissionAudit.needsShellSession(setOf(g), okInProcess = setOf(g)),
                "$g chỉ đọc được qua shell nên vẫn phải mở phiên",
            )
        }
    }

    @Test
    fun `khong can gi - khong mo phien`() {
        assertFalse(PermissionAudit.needsShellSession(emptySet(), emptySet()))
    }

    // ───────────────────────────────── 3. missing = cần − đang OK ─────────────────────────────────

    @Test
    fun `du het thi missing rong`() {
        val required = setOf(Grant.NOTIF_LISTENER, Grant.VM_OVERLAY_OP)
        assertEquals(emptySet<Grant>(), PermissionAudit.missing(required, required))
    }

    @Test
    fun `missing giu thu tu enum de log on dinh`() {
        val required = setOf(Grant.DOZE_VIETMAP, Grant.NOTIF_LISTENER, Grant.VM_OVERLAY_OP)
        assertEquals(
            listOf(Grant.NOTIF_LISTENER, Grant.VM_OVERLAY_OP, Grant.DOZE_VIETMAP),
            PermissionAudit.missing(required, emptySet()).toList(),
        )
    }

    @Test
    fun `quyen dang OK ma KHONG duoc yeu cau thi khong lam missing sai`() {
        assertEquals(
            setOf(Grant.NOTIF_LISTENER),
            PermissionAudit.missing(setOf(Grant.NOTIF_LISTENER), setOf(Grant.ASSISTANT, Grant.DOZE_SELF)),
        )
    }

    // ─────────────────────────────── 4. Parser: appops get <pkg> <OP> ───────────────────────────────

    @Test
    fun `appop allow - dung dinh dang thuc te tren xe`() {
        // Fixture thật: docs/refactor-car-execution/fixtures/appops-get-clusternav.txt
        assertTrue(
            PermissionAudit.appopAllowed(
                "SYSTEM_ALERT_WINDOW: allow; time=+37m46s20ms ago; duration=-24855d3h14m7s342ms",
            ),
        )
    }

    @Test
    fun `appop allow dang in gon`() {
        assertTrue(PermissionAudit.appopAllowed("SYSTEM_ALERT_WINDOW: allow"))
    }

    @Test
    fun `appop default - ignore - deny deu la CHUA CAP`() {
        for (mode in listOf("default", "ignore", "deny")) {
            assertFalse(
                PermissionAudit.appopAllowed("SYSTEM_ALERT_WINDOW: $mode"),
                "mode $mode không cho vẽ overlay ⇒ phải coi là chưa cấp",
            )
        }
    }

    @Test
    fun `appop output rong hoac No operations - CHUA CAP (chinh la ca cai lai ban mod VietMap)`() {
        assertFalse(PermissionAudit.appopAllowed(""))
        assertFalse(PermissionAudit.appopAllowed("No operations."))
    }

    @Test
    fun `appop khong bi lua boi chu allow o op KHAC`() {
        assertFalse(
            PermissionAudit.appopAllowed("PICTURE_IN_PICTURE: allow; time=+1m ago"),
            "op khác đang allow không có nghĩa overlay đã allow",
        )
    }

    @Test
    fun `appop doc dung op duoc yeu cau khi dump nhieu dong`() {
        val dump = """
            PICTURE_IN_PICTURE: ignore
            SYSTEM_ALERT_WINDOW: allow; time=+2m ago
        """.trimIndent()
        assertTrue(PermissionAudit.appopAllowed(dump))
        assertFalse(PermissionAudit.appopAllowed(dump, op = "PICTURE_IN_PICTURE"))
    }

    // ─────────────────────── 5. Parser: dumpsys deviceidle whitelist ───────────────────────

    @Test
    fun `doze whitelist - dong nguon,package,uid`() {
        val out = """
            system,com.android.providers.downloads,10012
            user,vn.vietmap.live,10145
        """.trimIndent()
        assertTrue(PermissionAudit.dozeWhitelisted(out, "vn.vietmap.live"))
        assertFalse(PermissionAudit.dozeWhitelisted(out, "com.byd.clusternav2"))
    }

    @Test
    fun `doze whitelist - khong khop nham tien to goi`() {
        assertFalse(
            PermissionAudit.dozeWhitelisted("user,vn.vietmap.livexx,10145", "vn.vietmap.live"),
            "so khớp phải theo segment, không phải contains",
        )
    }

    @Test
    fun `doze whitelist rong - coi nhu chua co`() {
        assertFalse(PermissionAudit.dozeWhitelisted("", "vn.vietmap.live"))
    }

    // ─────────────────── 6. Parser: secure list phẳng + secure pkg-class ───────────────────

    @Test
    fun `flat list co component - khop chinh xac tung phan tu`() {
        val comp = "com.byd.clusternav2/com.byd.clusternav.NavNotificationListener"
        val flat = "com.other.app/.Listener:$comp"
        assertTrue(PermissionAudit.flatHasComponent(flat, comp))
        assertFalse(PermissionAudit.flatHasComponent(flat, "com.byd.clusternav2/com.byd.clusternav.Other"))
    }

    @Test
    fun `flat list null - rong - literal null deu la CHUA BAT`() {
        val comp = "com.byd.clusternav2/com.byd.clusternav.NavNotificationListener"
        assertFalse(PermissionAudit.flatHasComponent(null, comp))
        assertFalse(PermissionAudit.flatHasComponent("", comp))
        assertFalse(PermissionAudit.flatHasComponent("null", comp))
    }

    @Test
    fun `flat list bo khoang trang quanh tung phan tu`() {
        val comp = "com.byd.clusternav2/com.byd.clusternav.NavNotificationListener"
        assertTrue(PermissionAudit.flatHasComponent(" com.other/.L : $comp ", comp))
    }

    @Test
    fun `secure service tro dung goi`() {
        val gsa = "com.google.android.googlequicksearchbox"
        assertTrue(
            PermissionAudit.serviceValuePointsTo(
                "$gsa/com.google.android.voiceinteraction.GsaVoiceInteractionService", gsa,
            ),
        )
        assertFalse(PermissionAudit.serviceValuePointsTo("com.byd.xiaodi/.AssistService", gsa))
        assertFalse(PermissionAudit.serviceValuePointsTo("null", gsa))
        assertFalse(PermissionAudit.serviceValuePointsTo(null, gsa))
        assertFalse(PermissionAudit.serviceValuePointsTo("", gsa))
    }

    // ─────────────────────────────── 7. AuditReport: verdict + log ───────────────────────────────

    @Test
    fun `report du het - allOk va khong can owner`() {
        val r = AuditReport(required = setOf(Grant.NOTIF_LISTENER), missingBefore = emptySet())
        assertTrue(r.allOk)
        assertFalse(r.needsOwner)
        assertFalse(r.didFixAnything)
    }

    @Test
    fun `report va duoc het - allOk (khong popup)`() {
        val r = AuditReport(
            required = setOf(Grant.VM_OVERLAY_OP),
            missingBefore = setOf(Grant.VM_OVERLAY_OP),
            fixed = setOf(Grant.VM_OVERLAY_OP),
            missingAfter = emptySet(),
        )
        assertTrue(r.allOk, "tự vá xong thì không được popup")
        assertTrue(r.didFixAnything)
    }

    @Test
    fun `report con thieu sau khi va - can owner`() {
        val r = AuditReport(
            required = setOf(Grant.NOTIF_LISTENER),
            missingBefore = setOf(Grant.NOTIF_LISTENER),
            missingAfter = setOf(Grant.NOTIF_LISTENER),
        )
        assertTrue(r.needsOwner)
        assertFalse(r.allOk)
    }

    @Test
    fun `report shell bi chan - can owner du khong biet thieu gi`() {
        val r = AuditReport(
            required = setOf(Grant.VM_OVERLAY_OP),
            shellBlocked = true,
            shellReason = "AWAITING_APPROVAL",
        )
        assertTrue(r.needsOwner, "không mở được dadb thì không thể kết luận là đủ")
        assertTrue(r.describe().contains("AWAITING_APPROVAL"))
    }

    @Test
    fun `describe in du 4 nhom theo thu tu enum`() {
        val r = AuditReport(
            required = setOf(Grant.DOZE_SELF, Grant.NOTIF_LISTENER),
            missingBefore = setOf(Grant.DOZE_SELF),
            fixed = setOf(Grant.DOZE_SELF),
        )
        assertEquals("cần=NOTIF_LISTENER,DOZE_SELF thiếu=DOZE_SELF đã vá=DOZE_SELF còn thiếu=-", r.describe())
    }

    // ───────── 7b. skipped: "chưa chạy" KHÁC "đã kiểm và đủ" (chống báo sai cho owner) ─────────

    @Test
    fun `luot bi bo - khong quay owner nhung cung KHONG duoc bao la da du`() {
        val skipped = AuditReport(skipped = true)
        assertTrue(skipped.allOk, "lượt bị bỏ thì im lặng — không popup")
        assertTrue(skipped.skipped, "nhưng bề mặt báo kết quả phải biết là CHƯA chạy, không được nói 'đã cấp đủ'")
        assertFalse(AuditReport(required = setOf(Grant.DOZE_SELF)).skipped, "lượt chạy thật thì không phải skipped")
    }

    // ───────── 7c. nagSignature: chỉ báo lại khi KẾT LUẬN đổi (notification boot, R8) ─────────

    @Test
    fun `nagSignature giong nhau khi ket luan khong doi - khac khi doi`() {
        val blocked = AuditReport(
            required = setOf(Grant.DOZE_SELF, Grant.VM_OVERLAY_OP),
            missingAfter = setOf(Grant.DOZE_SELF, Grant.VM_OVERLAY_OP),
            shellBlocked = true,
            shellReason = "PORT_CLOSED",
        )
        // Hai lần nổ máy, cùng một xe chưa cấp khoá adb ⇒ CÙNG chữ ký ⇒ chỉ báo notification một lần.
        assertEquals(blocked.nagSignature(), blocked.copy().nagSignature())
        // Lý do hỏng đổi (owner bấm Từ chối thay vì cổng đóng) ⇒ chữ ký đổi ⇒ được báo lại.
        assertNotEquals(blocked.nagSignature(), blocked.copy(shellReason = "AUTH_REJECTED").nagSignature())
        // Danh sách còn thiếu đổi ⇒ chữ ký đổi.
        assertNotEquals(
            blocked.nagSignature(),
            blocked.copy(missingAfter = setOf(Grant.DOZE_SELF)).nagSignature(),
        )
        // "Chặn ở shell" KHÁC "đọc được và thiếu đúng bấy nhiêu mục" — hai kết luận khác nhau, phải báo cả hai.
        assertNotEquals(
            blocked.nagSignature(),
            blocked.copy(shellBlocked = false, shellReason = null).nagSignature(),
        )
    }

    @Test
    fun `nagSignature khong phu thuoc thu tu tap hop`() {
        val a = AuditReport(missingAfter = linkedSetOf(Grant.DOZE_SELF, Grant.NOTIF_LISTENER))
        val b = AuditReport(missingAfter = linkedSetOf(Grant.NOTIF_LISTENER, Grant.DOZE_SELF))
        assertEquals(a.nagSignature(), b.nagSignature())
    }

    // ─────────────────── 8. FloatAppList.contains — cổng "đọc trước" của float list ───────────────────

    @Test
    fun `float list contains - khop chinh xac, khong khop tien to`() {
        assertTrue(FloatAppList.contains("com.oem.launcher,vn.vietmap.live", "vn.vietmap.live"))
        assertFalse(FloatAppList.contains("com.oem.launcher,vn.vietmap.livexx", "vn.vietmap.live"))
        assertTrue(FloatAppList.contains(" com.a , vn.vietmap.live ", "vn.vietmap.live"))
    }

    @Test
    fun `float list contains - null hoac rong la CHUA CO`() {
        assertFalse(FloatAppList.contains("null", "vn.vietmap.live"))
        assertFalse(FloatAppList.contains("", "vn.vietmap.live"))
    }

    @Test
    fun `da co trong list thi merge khong doi gi (doc truoc = khong ghi lai)`() {
        val cur = "com.oem.launcher,vn.vietmap.live"
        assertTrue(FloatAppList.contains(cur, "vn.vietmap.live"))
        assertEquals(cur, FloatAppList.merge(cur, listOf("vn.vietmap.live")))
    }
}
