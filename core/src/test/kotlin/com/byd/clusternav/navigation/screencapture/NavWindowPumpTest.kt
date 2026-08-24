package com.byd.clusternav.navigation.screencapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B3.13r — khoá HÀNH VI của nhịp định kỳ cho vòng enum cửa sổ a11y.
 *
 * Các test dựng một **mô hình trung thực** của dây nối trong `NavAccessibilityService` ([Sim]) chứ không
 * kiểm từng hàm rời rạc: `AccessibilityService`/`Handler` không chạy được trên android.jar stub của JVM, nên
 * thứ duy nhất kiểm được off-car là CHÍNH SÁCH — và chính sách chỉ có nghĩa khi ba mảnh (throttle chung, mồi
 * theo event, tự tắt khi vắng) được ráp đúng thứ tự của service. Phần DÂY NỐI thật (Handler, cổng Prefs,
 * onUnbind/onDestroy) khoá bằng quét source ở `:app` — `NavWindowPumpWiringContractTest`.
 *
 * Đồng hồ mô phỏng bắt đầu ở 10_000 ms, KHÔNG phải 0: `tryEnum` giữ nguyên phép so của bản cũ
 * (`now - lastEnumAt < periodMs`, mốc khởi tạo 0), nên ở `now` < `periodMs` lần enum đầu bị chặn — hành vi
 * thật của máy vừa boot. Bắt đầu ở 10_000 là mô phỏng đúng ca thường gặp (service nối sau khi máy đã chạy).
 */
class NavWindowPumpTest {

    /** Mô hình dây nối trong service. Mỗi bước = 1 ms, y như `Handler` trên main looper. */
    private class Sim(
        val pump: NavWindowPump = NavWindowPump(),
        /** Máy có cửa sổ nav nào không tại thời điểm enum — đọc lại mỗi lần enum (đổi được giữa chừng). */
        var navWindowPresent: Boolean = true,
    ) {
        var now = 10_000L
            private set

        /** Số lần vòng enum THẬT SỰ chạy (qua được throttle) — đây là chi phí IPC trên xe. */
        var enumRuns = 0
            private set

        /**
         * Số lượt enum chạy VÀ thấy cửa sổ nav — tức số lượt ĐỦ ĐIỀU KIỆN nuôi `CaptureForegroundSource`
         * **trong mô hình này**. KHÔNG phải số lời gọi `CaptureForegroundSource.publish` thật: đường thật
         * còn ba cổng nữa (`SourceArbiter.allows`, `winner`/`candidate`, `NavSourceDwell.onTick` bầu ra
         * nguồn) mà mô hình cố ý không mang — chúng thuộc về `resolveNavWindowRegardlessOfFocus`, không
         * thuộc chính sách nhịp. Đừng trích số này ra doc như "N lần publish".
         */
        var publishes = 0
            private set

        private var tickDueAt: Long? = null

        /** Hàng đợi Handler còn treo lượt nhịp nào không (dùng để kiểm "không rò Runnable"). */
        val tickQueued: Boolean get() = tickDueAt != null

        /** Đường EVENT trong `onAccessibilityEvent`: enum trước (đường cũ), rồi mới mồi nhịp (đường mới). */
        fun navEvent() {
            runEnum()
            if (pump.arm()) schedule()
        }

        /** Service `onUnbind`/`onDestroy` hoặc cổng Prefs đóng. */
        fun stopPump() {
            tickDueAt = null            // removeCallbacks
            pump.stop()
        }

        fun advance(ms: Long) = repeat(ms.toInt()) { step() }

        private fun schedule() {
            tickDueAt = now + pump.periodMs
        }

        private fun step() {
            now++
            val due = tickDueAt ?: return
            if (now < due) return
            tickDueAt = null            // Runnable đã chạy, rời hàng đợi
            val saw = runEnum()         // onWindowPumpTick
            if (pump.onTickDone(saw)) schedule()
        }

        /** null = throttle chặn ⇒ KHÔNG có quan sát mới (đúng `lastEnumSawNavWindow = null` của service). */
        private fun runEnum(): Boolean? {
            if (!pump.tryEnum(now)) return null
            enumRuns++
            if (navWindowPresent) publishes++
            return navWindowPresent
        }
    }

    // ─── Nghiệm thu 3: app hiện trên cụm, NGƯỜI DÙNG KHÔNG CHẠM GÌ ⇒ vẫn phải nuôi được gate ────────────────

    /**
     * KHOÁ BÀI HỌC CHÍNH (B3.13r): sau MỘT event mồi, 5 giây KHÔNG có event a11y nào nữa thì vẫn phải có
     * lượt nuôi `CaptureForegroundSource`. Trước bản này con số đó là **0** (vòng enum chỉ chạy trong
     * `onAccessibilityEvent`) ⇒ không còn đường nào mở lại cổng của `ScreenCaptureNavSource.tick` sau khi
     * kênh ảnh im quá `SourceArbiter.STALE_MS` — xem KDoc [NavWindowPump] về vòng tròn đó. Sau B3.44 VietMap
     * không còn kênh notification nên đây là đường sống DUY NHẤT của nó.
     *
     * ⚠ [Sim.publishes] là biến đếm CỦA MÔ HÌNH ("enum chạy VÀ có cửa sổ nav"), KHÔNG phải số lời gọi
     * `CaptureForegroundSource.publish` thật: đường thật còn ba cổng nữa mà mô hình cố ý không mang
     * (`SourceArbiter.allows`, có `winner`/`candidate` nào không, `NavSourceDwell.onTick` có bầu ra nguồn
     * không). Nói cách khác test này khoá đúng một mệnh đề: **nhịp có chạy khi event im hay không**. Ca
     * "nhịp chạy mà vẫn 0 publish" (vd `PREFER_GMAPS` trong khi chỉ có cửa sổ VietMap) là CÓ THẬT và nằm
     * ngoài mô hình — nó được ghi ở KDoc [NavWindowPump.onTickDone] + backlog B3.13r-cost.
     */
    @Test
    fun `0 event trong 5 giay - van con publish CaptureForegroundSource`() {
        val sim = Sim()
        sim.navEvent()                      // app dẫn mở lên: một event mồi duy nhất
        val afterEvent = sim.publishes
        sim.advance(5_000)                  // 5 giây IM LẶNG hoàn toàn
        val byPump = sim.publishes - afterEvent
        assertTrue(byPump >= 1, "5 giây không event vẫn phải có ≥1 publish do NHỊP, đo được $byPump")
        // Và không được thưa hơn ngưỡng tươi: CaptureForegroundSource.FRESH_MS = 3000 ms.
        assertTrue(byPump >= 5_000 / CaptureForegroundSource.FRESH_MS, "nhịp phải dày hơn ngưỡng tươi 3 s")
    }

    /**
     * ĐỐI CHỨNG cho test trên — mô hình hành vi TRƯỚC B3.13r (không ai mồi nhịp, enum chỉ chạy theo event).
     * Nếu bản vá bị gỡ, `navEvent()` sẽ không còn schedule gì và số publish trong 5 giây tụt về đúng 0 này.
     */
    @Test
    fun `doi chung - khong co nhip thi 5 giay im lang = 0 publish`() {
        val sim = Sim()
        sim.pump.tryEnum(sim.now)           // đúng một lần enum theo event, KHÔNG mồi nhịp
        val before = sim.publishes
        sim.advance(5_000)
        assertEquals(before, sim.publishes, "không có nhịp thì im lặng = không publish thêm lần nào")
    }

    // ─── Nghiệm thu 2: nhịp mới KHÔNG được nâng trần chi phí IPC ───────────────────────────────────────────

    /**
     * KHOÁ TRẦN CHI PHÍ (bài học B3.30 — `WazeHudSource` poll 900 ms sinh ~4000 lệnh/giờ chạy vô điều kiện).
     * 10 giây mô phỏng, KHÔNG có event nào sau lần mồi ⇒ số lần enum ≤ 13 (10_000 / 800 = 12,5).
     */
    @Test
    fun `10 giay chi co nhip - so lan enum khong qua 13`() {
        val sim = Sim()
        sim.navEvent()
        sim.advance(10_000)
        assertTrue(sim.enumRuns <= 13, "trần 1/800ms ⇒ ≤13 lần trong 10 s, đo được ${sim.enumRuns}")
        assertTrue(sim.enumRuns >= 12, "nhịp phải thật sự chạy đều, đo được ${sim.enumRuns}")
    }

    /**
     * KHOÁ: event DÀY (20 Hz — GMaps/VietMap bắn `TYPE_WINDOW_CONTENT_CHANGED` liên tục khi bản đồ vẽ lại)
     * CỘNG nhịp vẫn KHÔNG vượt trần, vì cả hai đường chung MỘT `tryEnum`. Đây là vế "nhịp mới không được làm
     * tăng số lần enum/giây" — so với hôm nay, ca event-dày là ca enum đã chạy hết công suất sẵn.
     */
    @Test
    fun `10 giay event day 20Hz cong nhip - so lan enum van khong qua 13`() {
        val sim = Sim()
        repeat(200) {                       // 200 event / 10 s = 20 Hz
            sim.navEvent()
            sim.advance(50)
        }
        assertTrue(sim.enumRuns <= 13, "event dày + nhịp vẫn phải ≤13 lần / 10 s, đo được ${sim.enumRuns}")
    }

    /** KHOÁ: mồi lại khi nhịp ĐANG chạy không được sinh lượt thứ hai trong hàng đợi (nhân đôi tần suất). */
    @Test
    fun `arm chi doi hoi post khi nhip DANG TAT`() {
        val pump = NavWindowPump()
        assertTrue(pump.arm(), "nhịp đang tắt → phải post")
        assertFalse(pump.arm(), "nhịp đang chạy → KHÔNG post thêm")
        assertFalse(pump.arm(), "…kể cả khi event dồn dập")
    }

    // ─── Nghiệm thu: tự tắt khi không còn app nav nào hiển thị, và mồi lại được ────────────────────────────

    /**
     * KHOÁ: đóng hết app dẫn ⇒ nhịp phải TẮT (hàng đợi sạch, chi phí IPC về 0), không poll vĩnh viễn.
     */
    @Test
    fun `het cua so nav thi nhip TU TAT sau idleStopTicks`() {
        val sim = Sim()
        sim.navEvent()
        sim.advance(2_000)
        assertTrue(sim.tickQueued, "còn cửa sổ nav thì nhịp còn chạy")

        sim.navWindowPresent = false        // người dùng đóng hết app dẫn
        val runsAtClose = sim.enumRuns
        sim.advance(20_000)                 // 20 giây sau
        assertFalse(sim.tickQueued, "không còn cửa sổ nav ⇒ nhịp phải tắt hẳn")
        val extra = sim.enumRuns - runsAtClose
        assertTrue(
            extra <= NavWindowPump.IDLE_STOP_TICKS + 1,
            "sau khi vắng chỉ được enum thêm tối đa ${NavWindowPump.IDLE_STOP_TICKS + 1} lần, đo được $extra",
        )
    }

    /**
     * KHOÁ SỐ ĐẾM, KHÔNG PHẢI HẰNG SỐ (thêm 08-23 vòng 2 — mutant SỐNG SÓT).
     *
     * Test ngay trên tham số hoá theo chính [NavWindowPump.IDLE_STOP_TICKS], nên đổi hằng số 4 → 1 vẫn
     * XANH: nó khoá "có tự tắt", không khoá "tự tắt sau đúng mấy lượt". Ở đây dùng một pump có
     * `idleStopTicks` TƯỜNG MINH và số lượt viết bằng SỐ THẬT, cộng một chốt ghim giá trị mặc định — hai
     * thứ khác nhau, cần cả hai: mặc định là con số CHẠY TRÊN XE, còn ngữ nghĩa "đếm đủ N mới tắt" là hợp
     * đồng của lớp.
     */
    @Test
    fun `tu tat sau DUNG idleStopTicks luot vang, va mac dinh la 4`() {
        assertEquals(4, NavWindowPump.IDLE_STOP_TICKS, "hằng số chạy trên xe: 4 lượt ≈ 3,2 s")

        val pump = NavWindowPump(idleStopTicks = 3)
        assertTrue(pump.arm())
        assertTrue(pump.onTickDone(false), "lượt vắng thứ 1/3 — chưa được tắt")
        assertTrue(pump.onTickDone(false), "lượt vắng thứ 2/3 — chưa được tắt")
        assertFalse(pump.onTickDone(false), "lượt vắng thứ 3/3 — phải tắt ĐÚNG ở đây")
        assertFalse(pump.running)
    }

    /**
     * KHOÁ `arm()` XOÁ CHUỖI VẮNG MẶT (thêm 08-23 vòng 2 — mutant SỐNG SÓT: gỡ `idleTicks = 0` khỏi `arm`
     * thì không test nào đỏ, dù KDoc mô tả nó là load-bearing).
     *
     * Ca thật: app dẫn CÒN sống và còn bắn event, nhưng `getWindows()` trả rỗng vài lượt liên tiếp (đang
     * chuyển cảnh / đổi display / cast vừa dựng lại). Không xoá chuỗi thì nhịp tính đủ [NavWindowPump
     * .IDLE_STOP_TICKS] rồi TẮT OAN giữa lúc đang dẫn — và app đang im event thì không ai mồi lại.
     */
    @Test
    fun `event nav xoa chuoi vang mat`() {
        val pump = NavWindowPump(idleStopTicks = 4)
        assertTrue(pump.arm())
        repeat(3) { assertTrue(pump.onTickDone(false), "3 lượt vắng liên tiếp: chưa tới ngưỡng") }

        assertFalse(pump.arm(), "nhịp đang chạy ⇒ không post thêm…")
        // …nhưng chuỗi vắng mặt phải bị xoá: 3 lượt vắng nữa vẫn chưa được tắt.
        repeat(3) { assertTrue(pump.onTickDone(false), "sau khi có event, đếm lại từ 0") }
        assertTrue(pump.running, "arm() phải xoá chuỗi vắng — thiếu nó, nhịp đã tắt oan ở lượt thứ 4")
        assertFalse(pump.onTickDone(false), "và chỉ tắt khi đủ 4 lượt vắng LIÊN TIẾP tính từ lần arm cuối")
    }

    /**
     * KHOÁ CLAUDE.md §3 (bài học `sats >= 4`): đường phục hồi KHÔNG được gate bằng dữ liệu mà chỉ chính nó
     * mới làm mới được. Nhịp đã tắt vì không thấy cửa sổ nav; thứ mồi nó dậy phải là EVENT a11y — đường độc
     * lập hoàn toàn với vòng enum.
     */
    @Test
    fun `nhip da tat van duoc EVENT nav moi lai`() {
        val sim = Sim()
        sim.navEvent()
        sim.navWindowPresent = false
        sim.advance(20_000)
        assertFalse(sim.tickQueued, "tiền đề: nhịp đã tắt")

        sim.navWindowPresent = true         // mở lại app dẫn → hệ thống bắn TYPE_WINDOW_STATE_CHANGED
        sim.navEvent()
        val afterEvent = sim.publishes
        sim.advance(5_000)
        assertTrue(sim.tickQueued, "event nav phải mồi lại được nhịp")
        assertTrue(sim.publishes - afterEvent >= 1, "và nhịp phải nuôi lại gate")
    }

    /** KHOÁ: nhịp bị throttle nuốt (event xen vào) KHÔNG được tính là "vắng" → nhịp không tự tắt oan. */
    @Test
    fun `nhip bi throttle nuot khong duoc tinh la vang`() {
        val pump = NavWindowPump()
        pump.arm()
        repeat(100) { assertTrue(pump.onTickDone(null), "quan sát null ⇒ nhịp vẫn phải sống") }
        assertTrue(pump.running)
    }

    // ─── Degrade-safe / vòng đời ──────────────────────────────────────────────────────────────────────────

    /**
     * KHOÁ: `stop()` (unbind/destroy/cổng Prefs đóng) hạ cờ nhưng KHÔNG xoá mốc throttle — dừng-rồi-mồi-lại
     * không được mở thêm một khe enum ngoài trần.
     */
    @Test
    fun `stop ha co nhung KHONG mo them khe enum ngoai tran`() {
        val pump = NavWindowPump()
        assertTrue(pump.tryEnum(10_000))
        pump.arm()
        pump.stop()
        assertFalse(pump.running, "stop phải hạ cờ để event sau mồi lại được")
        assertFalse(pump.tryEnum(10_400), "mốc throttle vẫn còn ⇒ chưa đủ 800 ms thì không được enum")
        assertTrue(pump.tryEnum(10_800), "đủ 800 ms thì mới lại được")
    }

    /** KHOÁ: phiên mới (service (re)connect) quên SẠCH, kể cả mốc throttle — cùng khuôn `NavSourceDwell.reset`. */
    @Test
    fun `reset quen sach ca moc throttle`() {
        val pump = NavWindowPump()
        assertTrue(pump.tryEnum(10_000))
        pump.arm()
        pump.reset()
        assertFalse(pump.running)
        assertTrue(pump.tryEnum(10_100), "phiên mới: mốc throttle đã xoá nên enum đầu tiên được chạy ngay")
    }

    /** KHOÁ: chu kỳ nhịp phơi ra ngoài để service dùng CHUNG một con số cho postDelayed và throttle. */
    @Test
    fun `periodMs phoi ra ngoai va mac dinh trung throttle 800ms`() {
        assertEquals(800L, NavWindowPump.DEFAULT_PERIOD_MS)
        assertEquals(1234L, NavWindowPump(periodMs = 1234L).periodMs)
    }
}
