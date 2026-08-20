package com.byd.clusternav.navigation

/**
 * Kế hoạch một tick đầu ra của [com.byd.clusternav.navigation.NavOutputOwner] (T4, spec `b3-full-nav-capture`
 * §R3/§R4/§R5). Thuần dữ liệu — nói owner CHANNEL nào cần bắn frame này, hoặc phải CLEAR.
 *
 * "CỨ BẮN" (R3a/OQ4): kênh nào còn tươi thì bắn kênh đó, KHÔNG gate theo kênh khác (arrow không chờ lane,
 * lane không chờ camera…). [clear] chỉ bật khi TẤT CẢ kênh đã stale (không còn gì để giữ) → owner nhả frame.
 */
data class NavOutputPlan(
    val pushArrow: Boolean,
    val pushLane: Boolean,
    val pushCamera: Boolean,
    val clear: Boolean,
) {
    /** Có ít nhất một kênh cần bắn frame này. */
    val anyPush: Boolean get() = pushArrow || pushLane || pushCamera

    companion object {
        /** Không kênh nào tươi và cũng không cần clear (chưa từng hiện gì). */
        val IDLE = NavOutputPlan(pushArrow = false, pushLane = false, pushCamera = false, clear = false)
    }
}

/**
 * Logic QUYẾT ĐỊNH THUẦN (không Android) cho đường payload gốc B3 (T4): cho biết mức tươi của từng kênh
 * (`ScreenCaptureSignal.arrowFresh/laneFresh/cameraFresh`) → owner bắn kênh nào / khi nào clear, cộng hai
 * hàm map giá trị (lane-arrow code, camera icon code). Tách ra :core để KHOÁ bằng unit-test off-car
 * (giống [Maneuver]/[ManeuverSignature]); owner (:app) chỉ đọc `ScreenCaptureSignal` + gọi `BydHal`.
 *
 * KEEP-ALIVE (R-nf, giống [HudKeepAlivePolicy]): owner gọi tick định kỳ (nhịp [HudKeepAlivePolicy.DEFAULT_INTERVAL_MS]
 * = 250ms). Mỗi tick đọc lại mức tươi SỐNG của tín hiệu → [decide] trả cùng plan "bắn" khi còn tươi ⇒ RE-ASSERT
 * nội dung mỗi nhịp (OEM không blank); khi hết tươi ⇒ [NavOutputPlan.clear] = true ⇒ owner nhả frame ĐÚNG một lần.
 * Ngưỡng "hết tươi" là `ScreenCaptureSignal.STALE_MS` (6s) — HỢP LÝ cho nguồn ẢNH (capture tick 2Hz liên tục khi
 * đang đọc; gap > 6s = capture đã dừng), KHÁC trần 180s của đường DATA (noti GMaps có thể giãn 108s mà vẫn đang dẫn).
 */
object NavOutputDecision {

    /**
     * Từ mức tươi 3 kênh → [NavOutputPlan]. CỨ BẮN: mỗi kênh tươi = bắn kênh đó (độc lập). Không kênh nào tươi
     * ⇒ [NavOutputPlan.clear] = true (owner nhả frame). Không gate chéo kênh (R3a/OQ4).
     */
    fun decide(arrowFresh: Boolean, laneFresh: Boolean, cameraFresh: Boolean): NavOutputPlan {
        val anyFresh = arrowFresh || laneFresh || cameraFresh
        return NavOutputPlan(
            pushArrow = arrowFresh,
            pushLane = laneFresh,
            pushCamera = cameraFresh,
            clear = !anyFresh,
        )
    }

    /**
     * Map các mũi tên đi-được của MỘT làn → một mã ghi vào `LANE_n_GUIDANCE_ARROW_SET`.
     *
     * Dùng từ vựng AMAP NEW_ICON của làn-cụm ([Maneuver.toAmapIcon]) của mũi tên CHÍNH (phần tử đầu) — cùng
     * ngôn ngữ mà dải làn cụm OEM đọc. Làn rỗng (không rõ hướng) → 0 (không glyph).
     *
     * ⚠ ON-CAR-VERIFY (OQ2/OQ3): từ vựng glyph CHÍNH XÁC của register làn (làn nhiều hướng: thẳng+phải…) chưa
     * được chốt trên xe — đây là mã hợp lý, khoá bằng test + tinh chỉnh khi có crop làn thật.
     */
    fun laneArrowCode(arrows: List<Maneuver>): Int = arrows.firstOrNull()?.toAmapIcon() ?: 0

    /**
     * Mã icon cho `INSTRUMENT_GUIDE_INFO_CAMERA_SET`: 1 khi CÓ camera (bật icon), 0 khi không (clear).
     * ⚠ ON-CAR-VERIFY (OQ4): từ vựng loại camera (tốc-độ / đèn-đỏ …) chưa chốt — vòng này chỉ bật/tắt icon.
     */
    fun cameraIconCode(hasCamera: Boolean): Int = if (hasCamera) 1 else 0
}
