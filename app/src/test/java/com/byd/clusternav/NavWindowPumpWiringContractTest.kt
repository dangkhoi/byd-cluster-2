package com.byd.clusternav

import com.byd.clusternav.testsupport.KotlinSource
import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B3.13r — khoá phần DÂY NỐI của nhịp định kỳ cho vòng enum cửa sổ a11y.
 *
 * `AccessibilityService` + `android.os.Handler` không chạy được trên android.jar stub của JVM, nên phần này
 * khoá bằng QUÉT SOURCE — đúng kỹ thuật của [NavSourceDwellWiringContractTest] /
 * [ScreenCaptureNavSourceContractTest]. Chính sách THUẦN đã được `NavWindowPumpTest` (:core) phủ off-car.
 *
 * BỐI CẢNH (mức bằng chứng: ĐÃ CHỨNG MINH từ source — CLAUDE.md §2): trước 2026-08-23,
 * `grep -nE "postDelayed|Handler\(|Timer|ScheduledExecutor" NavAccessibilityService.kt` = **0 dòng** và
 * `resolveNavWindowRegardlessOfFocus` chỉ có ĐÚNG MỘT call site, nằm bên trong `onAccessibilityEvent` —
 * handler đó `return` ngay khi `pkg !in navPackages`.
 *
 * ⚠ Đính chính 08-23 vòng 2: hệ quả KHÔNG phải "hết tươi 3 s ⇒ cụm trống" (nhịp chụp tự nuôi qua
 * `SourceArbiter.shouldFeed(…, IMAGE)`), mà là VÒNG TRÒN — kênh ảnh im quá `STALE_MS` thì cổng của
 * `ScreenCaptureNavSource.tick` đóng, và thứ duy nhất mở lại cổng nằm SAU chính cổng đó. Lý lẽ đầy đủ ở
 * KDoc `NavWindowPump`.
 */
class NavWindowPumpWiringContractTest {

    private val access =
        SourceRoots.text("src/main/java/com/byd/clusternav/modules/navaccess/NavAccessibilityService.kt")

    private val tickFn get() = functionBody(access, "private fun onWindowPumpTick()")
    private val scheduleFn get() = functionBody(access, "private fun scheduleWindowPump()")
    private val stopFn get() = functionBody(access, "private fun stopWindowPump()")
    private val eventFn get() = functionBody(access, "override fun onAccessibilityEvent(event: AccessibilityEvent?)")
    private val enumFn get() = functionBody(access, "private fun resolveNavWindowRegardlessOfFocus(now: Long)")

    /**
     * NGHIỆM THU 1 — có nhịp chạy ĐỘC LẬP `onAccessibilityEvent`. Đây là vế "≥1 call site MỚI ngoài event
     * handler" của CLAUDE.md §8: compile xanh không có nghĩa là code chạy, hàm nhịp phải THẬT SỰ gọi vòng enum.
     */
    @Test
    fun `co call site enum NGOAI onAccessibilityEvent`() {
        val code = stripComments(access)
        val calls = Regex("""resolveNavWindowRegardlessOfFocus\(""").findAll(code).count()
        // 1 định nghĩa + 1 call site event (cũ) + 1 call site nhịp (mới).
        assertTrue(calls >= 3, "phải có ≥1 call site enum ngoài event handler, đếm được $calls (kể cả định nghĩa)")
        assertTrue(
            stripComments(tickFn).contains("resolveNavWindowRegardlessOfFocus("),
            "thân nhịp phải THẬT SỰ gọi vòng enum",
        )
        assertFalse(
            stripComments(eventFn).contains("private fun onWindowPumpTick"),
            "hàm nhịp không được nằm trong event handler",
        )
        // Nhịp thật sự được đưa vào hàng đợi Handler, không phải chỉ tồn tại như hàm chết.
        assertTrue(scheduleFn.contains("pumpHandler.postDelayed(windowPumpRunnable"), "nhịp phải được post")
        assertTrue(
            stripComments(access).contains("runCatching { onWindowPumpTick() }"),
            "Runnable phải gọi hàm nhịp",
        )
    }

    /**
     * NGHIỆM THU 2 — nhịp KHÔNG được nâng trần số lần enum/giây (bài học B3.30: `WazeHudSource` poll 900 ms
     * sinh ~4000 lệnh/giờ, chạy vô điều kiện). Hai chốt: chu kỳ post = ĐÚNG hằng số throttle, và cả hai đường
     * (event + nhịp) đi qua CÙNG một cổng `NavWindowPump.tryEnum`.
     */
    @Test
    fun `chu ky nhip = throttle enum, va CHUNG mot cong tryEnum`() {
        assertTrue(
            scheduleFn.contains("postDelayed(windowPumpRunnable, WINDOW_ENUM_THROTTLE_MS)"),
            "chu kỳ nhịp phải lấy ĐÚNG hằng số throttle — chép số ra hai nơi là mở đường nâng trần",
        )
        assertTrue(
            access.contains("NavWindowPump(periodMs = WINDOW_ENUM_THROTTLE_MS)"),
            "pump nhận đúng hằng số nhịp thật (hằng số chỉ nằm MỘT nơi)",
        )
        val enumCode = stripComments(enumFn)
        assertTrue(
            enumCode.contains("if (!navWindowPump.tryEnum(now)) return"),
            "vòng enum phải xin phép cổng throttle DÙNG CHUNG ngay dòng đầu",
        )
        // Không được có throttle RIÊNG nào sống lại cạnh cổng chung (hai sổ = hai trần = trần thật gấp đôi).
        assertEquals(
            0,
            Regex("""lastWindowEnumAt""").findAll(stripComments(access)).count(),
            "mốc throttle cũ phải đã chuyển hẳn vào NavWindowPump, không còn bản sao trong service",
        )
        // Mồi chỉ khi nhịp ĐANG TẮT: post mỗi event vừa nhân tần suất, vừa đẩy hạn nhịp ra xa mãi.
        assertTrue(
            stripComments(eventFn).contains("if (navWindowPump.arm()) scheduleWindowPump()"),
            "event chỉ được post khi arm() nói nhịp đang tắt",
        )
    }

    /**
     * RỦI RO ĐÃ BIẾT (B3.30) — nhịp BẮT BUỘC nằm sau cổng `Prefs.enabled` + `Prefs.accBooster`, và cổng phải
     * ở trong THÂN NHỊP chứ không chỉ ở chỗ mồi: người dùng tắt công tắc GIỮA CHỪNG thì lượt kế tiếp phải
     * chết ngay, không đợi tới lần (re)connect.
     */
    @Test
    fun `than nhip co cong Prefs enabled + accBooster va tat han khi cong dong`() {
        val code = stripComments(tickFn)
        assertTrue(code.contains("Prefs.enabled(ctx)"), "nhịp phải qua cổng Prefs.enabled")
        assertTrue(code.contains("Prefs.accBooster(ctx)"), "nhịp phải qua cổng Prefs.accBooster")
        val gate = code.indexOf("Prefs.enabled(ctx)")
        val resolve = code.indexOf("resolveNavWindowRegardlessOfFocus(")
        assertTrue(gate in 0 until resolve, "cổng phải đứng TRƯỚC lệnh enum, không phải sau")
        assertTrue(code.contains("stopWindowPump(); return"), "cổng đóng ⇒ tắt hẳn nhịp rồi return")
        // Đường event cũng đã có cổng này ở đầu handler — giữ nguyên, không được nới.
        assertTrue(
            stripComments(eventFn).contains("if (!Prefs.enabled(applicationContext) || !Prefs.accBooster(applicationContext)) return"),
            "cổng đầu event handler giữ nguyên",
        )
    }

    /**
     * NGHIỆM THU: nhịp phải DỪNG khi không còn app nav nào hiển thị — hẹn lượt sau CHỈ được đi qua quyết định
     * `NavWindowPump.onTickDone`, và quan sát của lượt phải lấy từ chính vòng enum.
     */
    @Test
    fun `hen luot sau CHI qua onTickDone, quan sat lay tu vong enum`() {
        val code = stripComments(tickFn)
        assertTrue(
            code.contains("if (navWindowPump.onTickDone(lastEnumSawNavWindow)) scheduleWindowPump()"),
            "chỉ hẹn lượt sau khi chính sách còn cho chạy",
        )
        // Lượt bị throttle nuốt phải báo "chưa có quan sát" (null), không được tính là vắng → tắt oan.
        val reset = code.indexOf("lastEnumSawNavWindow = null")
        val resolve = code.indexOf("resolveNavWindowRegardlessOfFocus(")
        assertTrue(reset in 0 until resolve, "phải xoá quan sát cũ TRƯỚC khi enum, nếu không nhịp đọc lại số cũ")
        assertTrue(
            stripComments(enumFn).contains("lastEnumSawNavWindow = ranked.isNotEmpty()"),
            "quan sát phải là kết quả xếp hạng cửa sổ nav thật của lượt đó",
        )
    }

    /**
     * DEGRADE-SAFE + KHÔNG RÒ HANDLER: service bị gỡ ⇒ nhịp chết theo cả ở `onUnbind` LẪN `onDestroy`
     * (`onUnbind` không được bảo đảm gọi trong mọi ca huỷ service). `removeCallbacks` so sánh THAM CHIẾU nên
     * Runnable phải là MỘT thể hiện giữ ở `val` — dựng lambda mới mỗi lần post là gỡ không bao giờ trúng.
     */
    @Test
    fun `onUnbind va onDestroy deu tat nhip, Runnable la mot the hien duy nhat`() {
        assertTrue(
            functionBody(access, "override fun onUnbind(intent: android.content.Intent?): Boolean")
                .contains("stopWindowPump()"),
            "onUnbind phải tắt nhịp",
        )
        assertTrue(
            functionBody(access, "override fun onDestroy()").contains("stopWindowPump()"),
            "onDestroy phải tắt nhịp",
        )
        assertTrue(stopFn.contains("pumpHandler.removeCallbacks(windowPumpRunnable)"), "phải gỡ khỏi hàng đợi")
        assertTrue(stopFn.contains("navWindowPump.stop()"), "và hạ cờ để event sau mồi lại được")
        assertTrue(
            access.contains("private val windowPumpRunnable = Runnable {"),
            "Runnable phải là MỘT thể hiện giữ ở val (removeCallbacks so sánh tham chiếu)",
        )
        // Phiên mới (re)connect không được thừa kế Runnable/mốc của phiên trước.
        val connected = functionBody(access, "override fun onServiceConnected()")
        assertTrue(connected.contains("stopWindowPump()"), "(re)connect phải dọn hàng đợi trước")
        assertTrue(connected.contains("navWindowPump.reset()"), "(re)connect phải reset pump")
    }

    /**
     * MỒI NGUỘI (thêm 08-23 vòng 2) — nhịp chỉ có hai đường mồi, và đường EVENT không được bảo đảm sẽ tới:
     * service (re)connect trong lúc app dẫn ĐÃ hiển thị sẵn và đang im event là **chính ca bệnh** B3.13r,
     * lúc đó không còn ai đánh thức vòng enum. Mồi khống tự giới hạn (`onTickDone` tắt nhịp sau
     * `IDLE_STOP_TICKS` lượt vắng) và vẫn qua cổng `Prefs` trong thân nhịp.
     */
    @Test
    fun `onServiceConnected MOI NGUOI nhip, sau khi da reset`() {
        val connected = stripComments(functionBody(access, "override fun onServiceConnected()"))
        assertTrue(
            connected.contains("if (navWindowPump.arm()) scheduleWindowPump()"),
            "(re)connect phải tự mồi một lượt nhịp — không chờ event, vì ca bệnh là KHÔNG có event",
        )
        val reset = connected.indexOf("navWindowPump.reset()")
        val arm = connected.indexOf("navWindowPump.arm()")
        assertTrue(reset in 0 until arm, "phải reset TRƯỚC rồi mới mồi, nếu không reset xoá luôn cờ vừa dựng")
    }

    /**
     * CHỈ MỘT NƠI ĐƯỢC POST (thêm 08-23 vòng 2 — mutant SỐNG SÓT: thêm một `postDelayed(windowPumpRunnable,
     * …)` thứ hai trong thân nhịp thì không test nào đỏ). Trần enum vẫn do `tryEnum` giữ, nhưng hai nơi post
     * là hai lịch hẹn chồng nhau ⇒ churn Handler + hạn nhịp phụ thuộc nơi post sau cùng, tức chính sách
     * `NavWindowPump` không còn là nơi quyết định duy nhất.
     */
    @Test
    fun `chi CO MOT noi postDelayed nhip`() {
        val code = stripComments(access)
        assertEquals(
            1,
            Regex("""postDelayed\(windowPumpRunnable""").findAll(code).count(),
            "chỉ scheduleWindowPump() được post nhịp — thêm nơi post thứ hai là nhân lịch hẹn",
        )
        assertEquals(
            1,
            Regex("""private fun scheduleWindowPump\(\)""").findAll(code).count(),
            "và chỉ có MỘT hàm hẹn nhịp",
        )
    }

    /** DEGRADE-SAFE: mọi lối vào nhịp bọc `runCatching` — một ngoại lệ không được giết luồng main a11y. */
    @Test
    fun `moi loi vao nhip boc runCatching`() {
        val code = stripComments(access)
        assertTrue(code.contains("runCatching { onWindowPumpTick() }"), "Runnable bọc runCatching")
        assertTrue(
            stripComments(tickFn).contains("runCatching { resolveNavWindowRegardlessOfFocus("),
            "lệnh enum trong nhịp bọc runCatching",
        )
        assertTrue(scheduleFn.contains("runCatching {"), "post cũng bọc runCatching")
        assertTrue(stopFn.contains("runCatching { pumpHandler.removeCallbacks"), "gỡ hàng đợi cũng bọc")
        // Ngoại lệ ⇒ TẮT nhịp: `running` kẹt true mà hàng đợi rỗng thì arm() trả false vĩnh viễn
        // ⇒ nhịp chết luôn, không event nào mồi lại được.
        val runnable = access.substring(
            access.indexOf("private val windowPumpRunnable = Runnable {"),
            access.indexOf("override fun onServiceConnected()"),
        )
        assertTrue(runnable.contains("stopWindowPump()"), "lượt nhịp hỏng ⇒ tắt nhịp để event sau mồi lại")
    }

    /**
     * CLAUDE.md §6 — đường notification GMaps đang chạy NGOÀI HIỆN TRƯỜNG. Nhịp mới nằm ở CUỐI khối B3 của
     * event handler, TRƯỚC nhánh chỉ-GMaps, và tuyệt đối không chạm nhánh quét cự ly / arbiter.
     */
    @Test
    fun `nhip khong dung duong GMaps`() {
        val code = stripComments(eventFn)
        val arm = code.indexOf("navWindowPump.arm()")
        val enum = code.indexOf("resolveNavWindowRegardlessOfFocus(")
        val gmapsOnly = code.indexOf("if (pkg !in maps) return")
        assertTrue(enum in 0 until arm, "đường mới xuống CUỐI, sau vòng enum cũ (CLAUDE.md §6)")
        assertTrue(arm in 0 until gmapsOnly, "mồi nhịp đứng TRƯỚC nhánh chỉ-GMaps, không xen vào trong nó")
        val tick = stripComments(tickFn)
        assertFalse(tick.contains("SourceArbiter."), "nhịp KHÔNG được chạm trọng tài")
        assertFalse(tick.contains("scan("), "nhịp KHÔNG được chạm nhánh quét cự ly GMaps")
        assertFalse(tick.contains("rootInActiveWindow"), "nhịp KHÔNG được đọc cửa sổ đang focus")
    }

    private fun stripComments(src: String): String = KotlinSource.stripComments(src)

    /**
     * Thân hàm theo cặp ngoặc — **đếm trên source ĐÃ BỎ COMMENT/STRING** (sửa 08-23 vòng 2).
     *
     * Bản trước đếm `{`/`}` trên text THÔ: một ngoặc lẻ trong comment hay trong string literal (`"{"`) là
     * cắt ra sai thân hàm, và mọi assert bên dưới thành xanh/đỏ GIẢ. Cùng họ lỗi fail-open mà
     * `KotlinSource.stripComments` sinh ra để đóng. Hôm nay ngoặc còn cân bằng nên chưa lộ — đó đúng là
     * định nghĩa của bẫy bảo trì.
     */
    private fun functionBody(raw: String, signature: String): String {
        val text = KotlinSource.stripComments(raw)
        val start = text.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        var depth = 0
        var opened = false
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> { depth++; opened = true }
                '}' -> if (opened && --depth == 0) return text.substring(start, i + 1)
            }
        }
        error("unterminated $signature")
    }
}
