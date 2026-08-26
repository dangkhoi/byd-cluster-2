package com.byd.clusternav.navigation

import com.byd.clusternav.navigation.screencapture.ArrowSample

/**
 * DỰNG [NavigationFrameContent] — **một chỗ duy nhất** biến tín hiệu thô của MỘT nguồn dẫn đường thành khung
 * chuẩn đi vào cửa chính (`NavRepository.ingest*` → [NavigationSessionCoordinator] → làn cụm + cụm-centre + HUD).
 *
 * VÌ SAO CÓ FILE NÀY (F4, spec `docs/specs/nav-input-output-architecture.html`): kiến trúc duyệt 08-24 là
 * *"nhiều cách nhận tín hiệu → MỘT cửa vào → nhiều cửa ra"*. Trước đó đường THÔNG BÁO dựng khung ngay trong
 * `NavRepository.ingest` còn đường ẢNH tự ghi thẳng HAL ở một owner khác ⇒ hai cửa, và cửa thứ hai không mở
 * được chốt phiên nên VietMap/Waze không lên HUD. Tách phép dựng khung ra đây để CẢ HAI đường dùng CHUNG một
 * phép biến đổi, và để khoá nó bằng unit-test off-car (`:core`).
 *
 * THUẦN: không Android, không I/O, không đọc holder toàn cục — mọi thứ đi qua tham số. Gọi được từ luồng nào
 * cũng được.
 *
 * ⚠ [fromNotification] là **PHÉP DỜI CHỖ NGUYÊN VĂN** của biểu thức từng nằm trong `NavRepository.ingest`
 * (đường Google Maps đã proven ngoài hiện trường — CLAUDE.md §6). Không sửa một nhánh nào ở đây mà không có
 * bằng chứng: `GmapsContentGoldenTest` đông cứng đầu ra của nó theo bảng vàng.
 */
object NavContentBuilder {

    /** Không đọc được cự-ly. Cùng quy ước với `ARROW_DISTANCE_UNKNOWN` của đường ảnh và `-1` của HAL. */
    const val DISTANCE_UNKNOWN = -1

    /**
     * Khung dựng từ một frame THÔNG BÁO (Google Maps hôm nay — xem `NavApps.NOTIFICATION`).
     *
     * Tham số là đúng 6 trường của `NavState` mà khung cần; phần còn lại của `NavState` (bitmap mũi tên,
     * `active`, `updatedAt`) là việc của UI, không vào khung.
     *
     * Biểu thức bên dưới GIỮ NGUYÊN VĂN bản đang chạy ngoài hiện trường — kể cả hai lời gọi
     * [NavParse.parseEta] tách rời (không gộp thành một biến): gộp là "viết lại", mà việc này chỉ được phép
     * "dời chỗ". Khoá bằng `NavContentBuilderTest` (quét source) + `GmapsContentGoldenTest` (bảng vàng).
     */
    fun fromNotification(
        maneuverIcon: Int,
        maneuverText: String,
        distance: String,
        road: String,
        eta: String,
        maneuver: Maneuver?,
    ): NavigationFrameContent = NavigationFrameContent(
        maneuverCode = maneuverIcon.takeIf { it >= 0 },
        maneuverText = maneuverText.takeIf(String::isNotBlank),
        distanceMeters = NavParse.parseMeters(distance).takeIf { it >= 0 },
        roadName = road.takeIf(String::isNotBlank),
        etaEpochMs = null,
        routeRemainingMeters = NavParse.parseEta(eta).first.takeIf { it >= 0 },
        routeRemainingSeconds = NavParse.parseEta(eta).second.takeIf { it >= 0 },
        arrivalClock = NavParse.extractArrivalClock(eta),
        // Chốt maneuver TRUNG LẬP MỘT LẦN tại đây (biên đầu vào): ưu tiên maneuver đã có sẵn
        // (nguồn trực tiếp), nếu không thì bắc cầu từ mã AMAP đã phân loại. Cả hai đầu ra encode từ đây.
        maneuver = maneuver ?: Maneuver.fromAmapIcon(maneuverIcon),
    )

    /**
     * Khung dựng từ nguồn ẢNH (VietMap/Waze): mũi tên do screen-capture phân loại + số liệu đọc bằng a11y.
     *
     * @param framePkg DANH TÍNH của khung (`NavOutputPlan.framePkg`) — nguồn sự thật §R-BI.
     * @param arrow    mẫu mũi tên của chính khung này. `null` / không suy được hướng ⇒ trả `null`, **KHÔNG**
     *                 dựng khung (degrade-safe: thà không hiện còn hơn hiện mũi tên bịa).
     * @param reading  ảnh chụp a11y gần nhất. CHỈ được dùng khi `reading.pkg == framePkg`
     *                 ([NavFrameIdentity.sameFrame]) — bất biến MỘT-PACKAGE-MỘT-KHUNG; lệch chủ ⇒ bỏ HẾT
     *                 (tên đường/ETA/quãng còn lại), không lấy nửa này ghép nửa kia.
     * @param distanceMeters cự-ly ĐÃ QUA guard [TurnDistancePlausibility] ở tầng `:app` (< 0 = không tin
     *                 được). Cố ý KHÔNG tự đọc `reading.turnMeters`: guard là stateful (warmup, cạnh xuống)
     *                 nên phải sống ở owner, còn hàm này phải thuần.
     *
     * ⚠ GIỚI HẠN ĐÃ BIẾT — nguồn ẢNH KHÔNG có `maneuverText`. Hệ quả: `NavFormat.roundaboutExit` ở lambda làn
     * chỉ còn đọc được tên đường ⇒ VietMap/Waze **không ép được HUD icon 24+N (số lối ra vòng xuyến)**, rơi về
     * `Maneuver.toHudIcon()` generic. Đây là GIỮ NGUYÊN hiện trạng (đường ảnh trước nay cũng chỉ đẩy icon
     * generic), KHÔNG phải hồi quy — đừng đọc nhầm thành bug mới.
     */
    fun fromImage(
        framePkg: String,
        arrow: ArrowSample?,
        reading: NavViewIdSource.Reading?,
        distanceMeters: Int = DISTANCE_UNKNOWN,
    ): NavigationFrameContent? {
        arrow ?: return null
        val maneuver = arrow.maneuver
            ?: arrow.amap?.let { Maneuver.fromAmapIcon(it) }
            ?: return null
        // MỘT-PACKAGE-MỘT-KHUNG: số liệu a11y của app KHÁC không được đứng cạnh mũi tên app này.
        val own = reading?.takeIf { NavFrameIdentity.sameFrame(framePkg, it.pkg) }
        return NavigationFrameContent(
            maneuverCode = arrow.amap,
            maneuverText = null,
            distanceMeters = distanceMeters.takeIf { it >= 0 },
            roadName = own?.road?.takeIf(String::isNotBlank),
            etaEpochMs = null,
            routeRemainingMeters = own?.routeMeters?.takeIf { it >= 0 },
            routeRemainingSeconds = own?.routeSeconds?.takeIf { it >= 0 },
            // `Reading.arrivalClock` đã được nơi sản xuất chuẩn hoá 24h, nhưng vẫn lọc lại qua
            // [NavParse.extractArrivalClock]: `NavigationFrameContent.init` NÉM nếu chuỗi không khớp
            // `\d{1,2}:\d{2}`, và một lần ném ở đây là 4 lần/giây trên luồng owner.
            arrivalClock = own?.arrivalClock?.let(NavParse::extractArrivalClock),
            maneuver = maneuver,
        )
    }

    /**
     * Khung KEEP-ALIVE cho nguồn ẢNH (F4b — fix "VietMap/Waze dark for many stretches").
     *
     * VÌ SAO CÓ: mũi tên glyph của nguồn ảnh (VietMap/Waze) hay hết tươi giữa hai lần phân loại thành công
     * (phủ sóng template chưa đủ / capture hụt nhịp), trong khi a11y (cự-ly + tên đường) vẫn đọc đều mỗi
     * ~1,25 Hz suốt lúc đang dẫn. Trước đây mũi tên hết tươi ⇒ `NavOutputOwner` nhả CẢ phiên ⇒ cụm tắt đen,
     * DÙ app vẫn đang dẫn (a11y còn tươi). Hàm này dựng khung giữ HƯỚNG-LẦN-CUỐI [maneuver] + cự-ly/đường
     * a11y TƯƠI để cụm sống tiếp thay vì tắt.
     *
     * KHÁC [fromImage]: nhận [maneuver] TRỰC TIẾP (không [ArrowSample], vì mũi tên đã hết tươi). Giữ NGUYÊN
     * guard MỘT-PACKAGE-MỘT-KHUNG cho tên đường/ETA; `maneuverCode = maneuver.toAmapIcon()` (đồng bộ đường ảnh).
     *
     * ⚠ AN TOÀN 'im lặng > sai hướng' KHÔNG nằm ở đây mà ở CALLER (`NavOutputOwner.tryKeepAlive`): caller CHỈ
     * gọi hàm này khi cự-ly a11y KHÔNG TĂNG so với lần cuối (đang tiến tới CÙNG khúc rẽ). Cự-ly tăng = đã qua
     * khúc rẽ ⇒ khúc MỚI (hướng chưa xác nhận) ⇒ caller KHÔNG gọi hàm này, nhường im lặng. Hàm thuần này chỉ
     * dựng khung với hướng được đưa vào — nó KHÔNG tự phán đoán khúc rẽ mới.
     */
    fun fromKeepAlive(
        framePkg: String,
        maneuver: Maneuver,
        reading: NavViewIdSource.Reading?,
        distanceMeters: Int = DISTANCE_UNKNOWN,
    ): NavigationFrameContent {
        val own = reading?.takeIf { NavFrameIdentity.sameFrame(framePkg, it.pkg) }
        return NavigationFrameContent(
            maneuverCode = maneuver.toAmapIcon(),
            maneuverText = null,
            distanceMeters = distanceMeters.takeIf { it >= 0 },
            roadName = own?.road?.takeIf(String::isNotBlank),
            etaEpochMs = null,
            routeRemainingMeters = own?.routeMeters?.takeIf { it >= 0 },
            routeRemainingSeconds = own?.routeSeconds?.takeIf { it >= 0 },
            arrivalClock = own?.arrivalClock?.let(NavParse::extractArrivalClock),
            maneuver = maneuver,
        )
    }
}
