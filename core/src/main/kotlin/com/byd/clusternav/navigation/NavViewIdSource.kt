package com.byd.clusternav.navigation

/**
 * Dữ liệu dẫn đường đọc bằng **view-id a11y** — clone cách của OpenBYD
 * (`BydAccessibilityService`: `findAccessibilityNodeInfosByViewId("<pfx>:id/navBarDistance")`…).
 *
 * ── VÌ SAO (đo 2026-08-22) ────────────────────────────────────────────────────────────────────────────────
 * Trước đó ta kết luận nhầm rằng Waze "không có kênh dữ liệu": notification lúc đang dẫn chỉ có
 * `tickerText=Waze`, và `uiautomator dump` không thấy banner. Cả hai bằng chứng đều SAI HƯỚNG vì:
 *
 *  1. App mình **đã tắt `flagReportViewIds`** từ v0.36 ⇒ `findAccessibilityNodeInfosByViewId()` luôn trả
 *     rỗng, bất kể app kia có phơi id hay không. Không phải app kia không có — là mình không hỏi được.
 *  2. `uiautomator dump` chỉ chụp **active window**; OpenBYD cố ý duyệt **mọi** window
 *     (`getWindowsOnAllDisplays`) rồi mới tra id.
 *  3. **WazeMod là Waze đóng gói lại**: tiền tố của `viewIdResourceName` là tên package trong
 *     `resources.arsc`, KHÔNG phải applicationId. Đo tĩnh bằng aapt2: manifest `com.chisadin.wazemod`
 *     nhưng `Package name=com.waze id=7f` ⇒ id là `com.waze:id/...`. Nên phải thử CẢ hai tiền tố.
 *     ⚠ ĐÍNH CHÍNH: OpenBYD KHÔNG sai chỗ này — cổng của họ là `Collections.singleton("com.waze")`
 *     (`BydAccessibilityService.java:240`) nên root của bản mod bị loại TRƯỚC khi tra id; tiền tố của họ
 *     đúng theo cấu tạo. Ta cần hai tiền tố vì roster của TA có thêm bản mod.
 *
 * Bật lại cờ + sửa tiền tố thì đọc được ngay (đo thật, WazeMod đang dẫn):
 * ```
 * navBarDistance='140 m'  navBarStreetLine='Quang Trung'  lblArrivalTime='5:50 PM'
 * lblTimeToDestination='6 min'  lblDistanceToDestination='1.2 km'
 * navBarDirection=<ImageView> @Rect(60,45 - 135,120)   ← KHUNG VẼ mũi tên
 * ```
 *
 * ── GIÁ TRỊ ───────────────────────────────────────────────────────────────────────────────────────────────
 *  • Waze có **cự ly + tên đường + ETA** dạng CHỮ ⇒ không cần OCR.
 *  • `navBarDirection` cho **đúng khung vẽ** mà 38 template [ManeuverRegistry] được sinh ra từ đó ⇒ crop
 *    theo nó rồi khớp registry OpenBYD là đúng quy ước, không phải quy ước bbox-mực của [WazeArrowRegistry].
 *
 * ── HAI NGUỒN SẢN XUẤT, MỘT HOLDER (08-23) ───────────────────────────────────────────────────────────────
 * Holder này giờ nhận dữ liệu từ HAI cách đọc cây a11y, không chỉ view-id:
 *
 * | app | cách đọc | nơi sản xuất |
 * |---|---|---|
 * | Waze / WazeMod | `findAccessibilityNodeInfosByViewId` (clone OpenBYD) | `NavAccessibilityService.probeNavByViewId` |
 * | VietMap Live | `contentDescription` (Flutter ⇒ KHÔNG có resource-id) | `NavAccessibilityService.probeNavByContentDesc` → [VietMapDescParser] |
 *
 * VÌ SAO DÙNG CHUNG HOLDER thay vì thêm holder thứ hai cho VietMap (cân nhắc 08-23):
 *  1. **Bất biến MỘT-PACKAGE-MỘT-KHUNG đã nằm sẵn trong hợp đồng đọc**: [freshReadingFor] đòi
 *     `it.pkg == wantPkg` ([NavFrameIdentity] cùng một biểu thức), nên hai nguồn sản xuất KHÔNG thể trộn dữ
 *     liệu vào nhau — mẫu của VietMap chỉ trả lời cho câu hỏi về VietMap. Thêm holder thứ hai KHÔNG làm bất
 *     biến này chặt hơn một chút nào.
 *  2. **Consumer khỏi phải hỏi hai nơi**: `NavOutputOwner.plausibleSegOrUnknown` và
 *     `NavAccessibilityService.holderTurnMeters` chỉ hỏi MỘT holder. Hai holder ⇒ mỗi consumer phải tự viết
 *     luật "hỏi A trước hay B trước" — luật đó chép ra hai chỗ là bắt đầu lệch nhau (CLAUDE.md §4.1).
 *  3. Nhờ (1)+(2), VietMap được [NavSourceDwell] guard cam-kết-rẽ bảo vệ MIỄN PHÍ: `holderTurnMeters` đọc
 *     đúng holder này, hạn chế "VietMap ⇒ luôn -1" đã hết.
 *
 * ⚠ HỆ QUẢ: **một-ô** nghĩa là nguồn publish sau ghi đè nguồn publish trước. An toàn được là nhờ vòng enum
 * chỉ gọi `publishViewIdReading` cho nguồn ĐÃ ĐƯỢC [NavSourceDwell] bầu — dò thì CÂM. Đừng phá luật đó.
 *
 * TÊN `NavViewIdSource` giờ hơi hẹp so với nội dung (nó là "ô cự-ly đọc từ cây a11y"), nhưng đổi tên sẽ chạm
 * ~10 file test/contract đang khoá hành vi khác — không đáng đổi trong cùng một lần thay đổi hành vi.
 *
 * Thuần dữ liệu, không Android — tầng `:app` đọc node rồi publish vào đây.
 */
object NavViewIdSource {

    /** Coi là còn dùng được trong bao lâu. Nav bar cập nhật vài lần/giây khi đang dẫn. */
    const val FRESH_MS = 4000L

    // ⚠ KHÔNG có field rời (`turnMeters`/`road`/`atMs`… ) nữa — chúng từng tồn tại song song với [reading],
    // được [publish]/[clear] ghi nhưng KHÔNG consumer nào đọc (mọi đường đọc đi qua [freshReadingFor]). Giữ
    // chúng là để ngỏ đúng khe đọc-xé mà [Reading] sinh ra để đóng, và là nợ CLAUDE.md §8 (state không có
    // call site đọc). Trạng thái DUY NHẤT của holder là [reading].

    /**
     * Ảnh chụp NHẤT QUÁN của MỘT lần đọc. Gộp thành một object thay vì nhiều `@Volatile` rời để consumer
     * không thể ghép `road` của mẫu N với `turnMeters` của mẫu N+1 (đọc rách → guard thấy "đổi tên đường"
     * giả rồi cho lọt một cú nhảy cự ly thật).
     *
     * ⚠ MIỀN của [arrivalClock] là **"H:MM" 24 GIỜ**, do CẢ HAI nơi sản xuất bảo đảm (sửa 08-23 vòng 3):
     * Waze phơi 12 h (`lblArrivalTime='5:50 PM'`) nên `NavAccessibilityService.probeNavByViewId` đổi qua
     * [NavParse.extractArrivalClock24] TRƯỚC khi ghi; VietMap vốn đã 24 h ([VietMapDescParser.parseEta]).
     * Trước đó Waze ghi THÔ ⇒ một ô hai miền, và consumer đầu tiên sẽ nhận một trong ba kết cục đã ĐO:
     * sai 12 tiếng · `NavigationFrame.init` require NÉM · `BydHal` §ETA_H rụng phút. Đừng ghi thô lại.
     *
     * ⚠ [arrivalClock] / [routeSeconds] / [routeMeters] hiện **CHƯA có consumer** — ta đọc được chúng từ
     * Waze (`lblArrivalTime` / `lblTimeToDestination` / `lblDistanceToDestination`) nhưng đường ra của Waze
     * là `BydHal.pushNavigation(icon, seg)`, vốn không mang ETA. Muốn dùng thì phải cho Waze đi qua đường
     * `writeNavFrame` (đường này ĐÃ nhận `routeSeconds`/`routeMeters`/`arrivalClock`) — là việc riêng, cần
     * cân nhắc vì hiện `NavigationHudOwner` là chủ độc quyền của register đó.
     */
    data class Reading(
        val pkg: String,
        val turnMeters: Int,
        val road: String,
        val atMs: Long,
        val arrivalClock: String = "",
        val routeSeconds: Int = -1,
        val routeMeters: Int = -1,
    )

    // Không phơi accessor "đọc thô": mọi consumer đều phải nói RÕ mình muốn mẫu của app nào, tại thời điểm
    // nào (CLAUDE.md §8 — hàm không call site là nợ; và đọc thô là mở lại khe dùng mẫu của app khác).
    @Volatile private var reading: Reading? = null


    /**
     * Ảnh chụp còn dùng được cho [wantPkg] tại [now]: đúng chủ + còn tươi + CÓ cự ly (`turnMeters >= 0`).
     * Không thoả một điều kiện nào ⇒ null (im lặng, không đoán).
     */
    fun freshReadingFor(wantPkg: String, now: Long, staleMs: Long = FRESH_MS): Reading? =
        reading?.takeIf { it.pkg == wantPkg && it.turnMeters >= 0 && it.atMs > 0L && now - it.atMs <= staleMs }

    /**
     * Cự ly còn dùng được cho [pkg] này không.
     * Viết lại qua [freshReadingFor] (B-III) — CÙNG điều kiện, CÙNG kết quả; các test cũ của hàm này xanh
     * nguyên vẹn chính là bằng chứng tương đương.
     */
    fun freshTurnMetersFor(wantPkg: String, now: Long): Int = freshReadingFor(wantPkg, now)?.turnMeters ?: -1

    fun publish(
        pkg: String,
        turnMeters: Int,
        road: String,
        arrivalClock: String,
        routeSeconds: Int,
        routeMeters: Int,
        now: Long,
    ) {
        // MỘT phép gán duy nhất, vào một object BẤT BIẾN: consumer hoặc thấy mẫu cũ trọn vẹn, hoặc thấy mẫu
        // mới trọn vẹn — không có trạng thái nửa vời nào để nhìn thấy.
        reading = Reading(pkg, turnMeters, road, now, arrivalClock, routeSeconds, routeMeters)
    }

    fun clear() {
        reading = null
    }
}
