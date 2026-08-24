package com.byd.clusternav.navigation.screencapture

/**
 * B3.13r — NHỊP ĐỊNH KỲ cho vòng enum cửa sổ a11y (`NavAccessibilityService.resolveNavWindowRegardlessOfFocus`).
 *
 * ── VÌ SAO (mức bằng chứng: ĐÃ CHỨNG MINH từ source, chưa đo trên xe — CLAUDE.md §2) ─────────────────────
 * Trước bản này, vòng enum CHỈ chạy bên trong `onAccessibilityEvent`, mà handler đó `return` ngay khi
 * `pkg !in navPackages`. `grep -nE "postDelayed|Handler\(|Timer|ScheduledExecutor" NavAccessibilityService.kt`
 * = **0 dòng** ⇒ không có nhịp nào chạy độc lập event.
 *
 * ⚠ **ĐÍNH CHÍNH 2026-08-24 vòng 2 — bệnh KHÔNG phải "im event 3 s là cụm trống ngay".** Bản vòng 1 viết
 * như vậy và nó SAI: `ScreenCaptureNavSource.tick` chỉ đòi `dataFresh || fgFresh`, mà `SourceArbiter
 * .shouldFeed(pkg, …, NavChannel.IMAGE)` khi trả true lại tự đặt `activeSource = pkg; activeSeen = now`
 * (SourceArbiter.kt), và `pkg` của nhịp chụp có nhánh rơi `?: SourceArbiter.activeSource`. Nghĩa là **mỗi lần
 * chụp + phân loại THÀNH CÔNG tự làm tươi chính cái cổng cho phép nó chạy** ⇒ chừng nào kênh ẢNH còn bắn
 * được thì vòng chụp TỰ NUÔI, không cần một event a11y nào, và `CaptureForegroundSource` hết tươi cũng không
 * làm trống cụm.
 *
 * **Bệnh THẬT là VÒNG TRÒN** — đúng họ lỗi `sats >= 4` mà CLAUDE.md §3 cấm: thứ DUY NHẤT làm tươi cổng đó
 * chính là kênh ảnh. Kênh ảnh im quá `SourceArbiter.STALE_MS` = 6 s là ca THƯỜNG XUYÊN chứ không hiếm —
 * [ĐO] B3.52: trên 87 khung VietMap chỉ **39** đi trọn tới mã AMAP, số còn lại IM LẶNG CÓ CHỦ Ý (thiếu
 * template ⇒ thà không vẽ còn hơn vẽ sai hướng). Qua 6 s đó `dataFresh` tắt; app im event thì `fgFresh` tắt
 * theo sau 3 s ⇒ `tick` return trắng ⇒ **từ giây đó không còn đường nào mở lại cổng**, vì đường mở lại
 * (chụp + phân loại) nằm SAU chính cái cổng vừa đóng. Sau B3.44 VietMap không còn kênh notification nên
 * không có nguồn thứ hai gỡ bí ⇒ nó câm tới khi người dùng chạm vào máy.
 *
 * Nhịp này là đường CẮT VÒNG TRÒN: nó làm tươi `CaptureForegroundSource` bằng một quan sát ĐỘC LẬP với kết
 * quả phân loại, nên `tick` vẫn được chạy để thử lại khung sau.
 *
 * ── CHI PHÍ (bài học B3.30, backlog: `WazeHudSource` poll 900 ms ⇒ ~4000 lệnh/giờ chạy VÔ ĐIỀU KIỆN) ──────
 * `flagRetrieveInteractiveWindows` + `getWindowsOnAllDisplays()` là IPC ĐẮT, chạy trên xe đang lăn bánh.
 * Ba chốt giữ chi phí:
 *  1. **MỘT throttle dùng chung** — cả đường event LẪN đường nhịp đều phải xin phép [tryEnum]. Trần tuyệt
 *     đối = 1 lần / [periodMs], **đúng bằng trần đang có hôm nay**; nhịp chỉ LẤP chỗ trống lúc event im.
 *  2. **Chu kỳ = ĐÚNG throttle** — caller truyền `WINDOW_ENUM_THROTTLE_MS`, hằng số chỉ nằm MỘT nơi.
 *  3. **Tự tắt** — [idleStopTicks] nhịp LIÊN TIẾP không thấy cửa sổ nav nào ⇒ [onTickDone] trả false ⇒
 *     service KHÔNG hẹn lại ⇒ hàng đợi Handler sạch, chi phí về 0 khi không có app dẫn nào trên máy.
 *
 * ⚠ **"Trần không đổi" chỉ đúng cho ĐỈNH, KHÔNG đúng cho TRUNG BÌNH** (đính chính vòng 2). Ca app dẫn hiển
 * thị mà IM EVENT trước đây tốn ~0 lệnh/giờ, nay tốn tới 1/[periodMs] = **~4500 lượt enum/giờ**, mỗi lượt
 * còn kéo theo [com.byd.clusternav.navigation.screencapture.NavWindowPicker] + một lượt dò cây a11y có chặn
 * cho từng ứng viên (`NavAccessibilityService.probeRanked`). Và khi lượt đó bầu ra được một nguồn, nó làm
 * tươi `CaptureForegroundSource` ⇒ giữ `ScreenCaptureNavSource` (2 Hz) chạy hết đường `am stack list` +
 * chụp màn: **~7200 lệnh shell + 7200 lần chụp/giờ** ở tầng dưới. Con số B3.30 bị coi là không chấp nhận
 * được là ~4000 lệnh/giờ ⇒ **chi phí thật của bản này CHƯA ĐO trên xe và là rủi ro đang mở** (backlog
 * B3.13r-cost). Đổi tiêu chí tự tắt sang "có app đang DẪN" (thay vì "có cửa sổ nav") là hướng đóng, nhưng
 * nó đụng đúng đường phục hồi nên phải đo trước — xem KDoc [onTickDone].
 *
 * ── ĐƯỜNG PHỤC HỒI KHÔNG VÒNG TRÒN (CLAUDE.md §3 — bài học `sats >= 4`) ──────────────────────────────────
 * Nhịp tự tắt, nhưng thứ MỒI nó dậy lại là [arm] — gọi từ `onAccessibilityEvent` và từ `onServiceConnected`,
 * hai đường HOÀN TOÀN ĐỘC LẬP với vòng enum. Mở một app dẫn luôn sinh `TYPE_WINDOW_STATE_CHANGED` của
 * package đó (nằm trong `packageNames` của `nav_accessibility_config.xml`) ⇒ nhịp được mồi lại. Tuyệt đối
 * KHÔNG gate việc khởi động lại bằng chính dữ liệu mà chỉ vòng enum mới làm mới được.
 *
 * THUẦN (không Android), không giữ đồng hồ riêng — caller bơm `now` MONOTONIC (`SystemClock.elapsedRealtime`,
 * cùng miền đồng hồ với `CaptureForegroundSource`/`NavSourceDwell`). Trạng thái là PHIÊN (per-instance);
 * service giữ MỘT thể hiện và gọi [reset] khi (re)connect — cùng khuôn [com.byd.clusternav.navigation.NavSourceDwell].
 */
class NavWindowPump(
    /**
     * Chu kỳ nhịp **và** khoảng cách tối thiểu giữa hai lần enum — MỘT con số cho cả hai việc, cố ý.
     * Caller PHẢI truyền `NavAccessibilityService.WINDOW_ENUM_THROTTLE_MS`.
     *
     * Vì sao chu kỳ ĐÚNG BẰNG throttle mà nhịp vẫn không bị chính throttle nuốt: `Handler.postDelayed(d)`
     * bảo đảm nổ **không sớm hơn** d (đo bằng `uptimeMillis`), còn [tryEnum] đo bằng `elapsedRealtime` —
     * đồng hồ CHẠY NHANH HƠN HOẶC BẰNG uptime (nó đếm cả lúc máy ngủ sâu). Vậy khoảng cách quan sát được ở
     * [tryEnum] luôn ≥ d. Nhịp bị nuốt chỉ xảy ra khi đường EVENT vừa enum xen vào giữa — ca đó [tryEnum]
     * trả false, service báo lại bằng `sawNavWindow = null` và nhịp KHÔNG bị tính là "vắng" (xem [onTickDone]).
     */
    val periodMs: Long = DEFAULT_PERIOD_MS,
    /** Bao nhiêu nhịp LIÊN TIẾP không thấy cửa sổ nav nào thì tắt hẳn nhịp (mặc định [IDLE_STOP_TICKS]). */
    private val idleStopTicks: Int = IDLE_STOP_TICKS,
) {

    /**
     * Có một lượt nhịp đang treo trong hàng đợi Handler hay không — **sổ sách nội bộ** của [arm]/[onTickDone].
     *
     * ⚠ Service KHÔNG đọc field này (đính chính vòng 2: quét `:app` không có call site đọc nào ngoài KDoc).
     * Nó rẽ nhánh bằng GIÁ TRỊ TRẢ VỀ của [arm]/[onTickDone] — cố ý, vì "đọc cờ rồi mới post" là hai bước có
     * khe ở giữa, còn "post đúng khi hàm nói post" thì không. Field vẫn `public` cho test đọc trạng thái sau
     * một chuỗi thao tác; đừng biến nó thành đường quyết định thứ hai.
     */
    var running: Boolean = false
        private set

    /**
     * Mốc lần enum GẦN NHẤT ĐÃ CHẠY (monotonic ms). 0 = chưa lần nào. Giữ nguyên phép so của bản cũ
     * (`now - lastEnumAt < periodMs`) để không đổi một li hành vi throttle đang chạy ngoài hiện trường.
     */
    private var lastEnumAt = 0L

    /** Số nhịp LIÊN TIẾP quan sát được "không có cửa sổ nav nào". Chạm [idleStopTicks] ⇒ tắt nhịp. */
    private var idleTicks = 0

    /**
     * CỔNG THROTTLE DÙNG CHUNG cho cả hai đường gọi vòng enum (event + nhịp). true = được phép enum lần này
     * (và đã đóng mốc). Đây là chốt số 1 của trần chi phí: dù event có dày tới đâu, dù nhịp có đúng hạn hay
     * không, số lần enum không bao giờ vượt 1 / [periodMs].
     */
    fun tryEnum(now: Long): Boolean {
        if (now - lastEnumAt < periodMs) return false
        lastEnumAt = now
        return true
    }

    /**
     * MỒI nhịp từ một đường ĐỘC LẬP với vòng enum. Hai call site, cùng ngữ nghĩa "có lý do tin rằng đáng
     * enum lại":
     *  · `onAccessibilityEvent` — có event a11y của một nav package (đã qua cổng `Prefs.enabled` +
     *    `Prefs.accBooster` ở caller);
     *  · `onServiceConnected` — phiên mới. Không có event nào bảo đảm sẽ tới nếu app dẫn ĐÃ hiển thị sẵn và
     *    đang im (đúng ca bệnh), nên phiên mới phải tự mồi một lượt. Mồi khống là RẺ và TỰ GIỚI HẠN: không
     *    có cửa sổ nav nào thì [idleStopTicks] lượt sau nhịp tự tắt.
     *
     * (Tên cũ `armOnNavEvent` đổi ngày 08-24 vòng 2 — nó chỉ đúng cho một trong hai call site.)
     *
     * @return true ⇒ service phải `postDelayed` một lượt nhịp (nhịp đang TẮT). false ⇒ nhịp đã chạy rồi,
     * **không được** post thêm (post thêm là nhân đôi tần suất, và post-mỗi-event còn đẩy hạn nhịp ra xa mãi
     * khi event dày ⇒ nhịp không bao giờ nổ).
     */
    fun arm(): Boolean {
        // LOAD-BEARING, không phải dọn dẹp: event của nav package = app đó CÒN SỐNG ⇒ xoá chuỗi vắng mặt.
        // Thiếu dòng này thì một app bắn event đều nhưng `getWindows()` trả rỗng vài lượt liên tiếp (đổi
        // display / đang chuyển cảnh) sẽ bị tính đủ [idleStopTicks] và nhịp tắt OAN. Khoá bằng
        // `NavWindowPumpTest.event nav xoa chuoi vang mat`.
        idleTicks = 0
        if (running) return false
        running = true
        return true
    }

    /**
     * Kết thúc MỘT lượt nhịp.
     *
     * @param sawNavWindow kết quả quan sát của lượt enum vừa rồi: true = có ít nhất một cửa sổ nav,
     *   false = không có cửa sổ nav nào, **null = KHÔNG có quan sát mới** (lượt enum bị [tryEnum] chặn vì
     *   đường event vừa enum xen vào). null KHÔNG được tính là "vắng": tính vào là nhịp tự tắt oan đúng lúc
     *   app dẫn đang bắn event dày nhất.
     * @return true ⇒ hẹn lượt nhịp kế tiếp; false ⇒ nhịp DỪNG (không còn cửa sổ nav nào).
     *
     * ⚠ HAI GIỚI HẠN ĐÃ BIẾT của tiêu chí "CÓ CỬA SỔ NAV" (ghi ra để đừng ai tưởng nó là "app đang DẪN"):
     *  1. **Chi phí**: app dẫn chỉ HIỂN THỊ mà không dẫn (VietMap do `VietMapAutostart` bật lúc boot, không
     *     có tuyến) vẫn giữ nhịp sống vô hạn — xem khối CHI PHÍ ở KDoc lớp. Đổi sang "có nguồn ĐỌC ĐƯỢC dữ
     *     liệu dẫn đường" thì đóng được, NHƯNG một lượt dò hụt thoáng qua sẽ tắt nhịp giữa lúc đang dẫn mà
     *     app im event — tức là đánh đổi chi phí lấy đúng cái bệnh đang chữa. Phải ĐO trên xe trước
     *     (backlog B3.13r-cost), không đổi mù.
     *  2. **Không thay được `NavSourceDwell` R0**: [IDLE_STOP_TICKS] × [periodMs] = 3,2 s < `holderGraceMs`
     *     (= `SourceArbiter.STALE_MS` = 6 s), nên chuỗi `onTick(candidate = null)` mà nhánh `ranked.isEmpty()`
     *     bơm vào dwell KHÔNG BAO GIỜ đủ dài để R0 `RELEASED` bắn qua đường nhịp. Không phải hồi quy (trước
     *     B3.13r không có nhịp nào cả), nhưng đừng viện nhịp làm đường nhả holder.
     */
    fun onTickDone(sawNavWindow: Boolean?): Boolean {
        if (sawNavWindow != null) {
            idleTicks = if (sawNavWindow) 0 else idleTicks + 1
        }
        running = idleTicks < idleStopTicks
        return running
    }

    /**
     * Dừng nhịp: cổng người dùng đóng (`Prefs.enabled`/`Prefs.accBooster` tắt), service `onUnbind`/`onDestroy`,
     * hoặc một lượt nhịp ném ngoại lệ. Service PHẢI `removeCallbacks` kèm theo — [running] chỉ là sổ sách.
     * KHÔNG xoá [lastEnumAt]: throttle là trần chi phí, dừng-rồi-mồi-lại không được phép mở một khe enum thêm.
     */
    fun stop() {
        running = false
        idleTicks = 0
    }

    /** Phiên mới (service (re)connect): quên sạch, kể cả mốc throttle. Cùng khuôn `NavSourceDwell.reset`. */
    fun reset() {
        running = false
        idleTicks = 0
        lastEnumAt = 0L
    }

    companion object {
        /** Trùng `NavAccessibilityService.WINDOW_ENUM_THROTTLE_MS`; chỉ là mặc định cho test, caller vẫn truyền. */
        const val DEFAULT_PERIOD_MS = 800L

        /**
         * 4 nhịp ≈ 3,2 s không thấy cửa sổ nav nào ⇒ tắt. THAM SỐ CHỌN, chưa đo trên xe: đủ dài để một lần
         * `getWindows()` trả rỗng nhất thời (đổi display / app đang chuyển cảnh) không giết nhịp, đủ ngắn để
         * đóng app dẫn xong là chi phí IPC về 0 trong vài giây.
         */
        const val IDLE_STOP_TICKS = 4
    }
}
