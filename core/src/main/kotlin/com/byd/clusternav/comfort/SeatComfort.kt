package com.byd.clusternav.comfort

/**
 * GHẾ — LÀM MÁT / SƯỞI TỰ ĐỘNG · pure model (không Android, unit-test off-car được).
 *
 * ── NGUỒN (RE app tham chiếu `com.byd.mecanum.dashboard`) ────────────────────────────────────────
 * Cơ chế: lệnh BYD HAL `(deviceType, featureId, value)`. `deviceType 1023` = ghế/tiện-nghi. App tham chiếu
 * gọi qua binder proxy `m.Y(sCommunicationBinder, 1023, featureId, value)`; ClusterNav KHÔNG dùng proxy đó —
 * nó đi ĐƯỜNG REFLECTION SẴN CÓ ([com.byd.clusternav.modules.hal.BydHal] `device(...)` + `setInt(...)`), CÙNG
 * HAL nền. `value` = mức ghế. Làm mát và sưởi LOẠI TRỪ NHAU (set cái này → xe reset cái kia).
 *
 * Bảng feature-id ghế (device 1023), mẫu `0x4310101{0,4,8,C}` rồi +8 mỗi hàng ghế:
 * | Ghế                     | COOL (làm mát) | HEAT (sưởi) |
 * |-------------------------|----------------|-------------|
 * | 0 · trước-trái (lái)    | 0x43101010     | 0x43101014  |  ← đã RE, proven trên xe owner
 * | 1 · trước-phải (phụ)    | 0x43101018     | 0x4310101C  |  ← đã RE, proven trên xe owner
 * | 2 · sau-trái (Han)      | 0x43101020     | 0x43101024  |  ← ⚠ SUY (+8/hàng) — CẦN XÁC NHẬN TRÊN XE
 * | 3 · sau-phải (Han)      | 0x43101028     | 0x4310102C  |  ← ⚠ SUY (+8/hàng) — CẦN XÁC NHẬN TRÊN XE
 *
 * Ánh xạ giá trị (lựa chọn người dùng → giá trị HAL): OFF=0, "Mức 1"=2, "Mức 2"=3 (xem [HAL_VALUE]).
 */
object SeatComfort {

    /** Chế độ TOÀN CỤC — chọn MỘT (loại trừ nhau, xe reset cái kia khi set cái này). */
    enum class SeatMode { COOL, HEAT }

    /** Cấp độ TẮT (0). Mức hoạt động là 1 ("Mức 1") và 2 ("Mức 2"). */
    const val LEVEL_OFF = 0

    /**
     * Một ghế: [index] (0=FL/lái, 1=FR/phụ, 2=RL, 3=RR), [labelKey] khoá nhãn (MainActivity dịch song ngữ qua
     * `Lang.t`), [coolFeatureId]/[heatFeatureId] = feature-id HAL cho làm mát / sưởi.
     */
    data class Seat(
        val index: Int,
        val labelKey: String,
        val coolFeatureId: Int,
        val heatFeatureId: Int,
    )

    /** Bảng ghế (theo bảng RE ở KDoc lớp). Rear (2,3) = feature-id SUY — cần xác nhận trên xe Han. */
    val SEATS: List<Seat> = listOf(
        Seat(0, "driver", 0x43101010, 0x43101014),
        Seat(1, "passenger", 0x43101018, 0x4310101C),
        Seat(2, "rear_left", 0x43101020, 0x43101024),   // EXTRAPOLATED (+8/hàng) — needs-on-car-confirm
        Seat(3, "rear_right", 0x43101028, 0x4310102C),   // EXTRAPOLATED (+8/hàng) — needs-on-car-confirm
    )

    /**
     * Ánh xạ cấp-độ-người-dùng (0/1/2) → giá trị HAL. index 0 = OFF, 1 = "Mức 1", 2 = "Mức 2".
     *
     * ⚠ CHỈNH DỄ nếu on-car sai: app tham chiếu (proven chạy trên xe owner) gửi **2** và **3** làm hai mức
     * hoạt động, **0** = tắt — nên để mặc định `intArrayOf(0, 2, 3)`. NẾU 2/3 không ăn trên xe thì ghế có thể
     * dùng **1/2** → đổi mảng này thành `intArrayOf(0, 1, 2)` là xong (chỉ một chỗ).
     */
    val HAL_VALUE: IntArray = intArrayOf(0, 2, 3)

    /** feature-id HAL cho [seatIndex] theo [mode] (COOL → coolFeatureId, HEAT → heatFeatureId). */
    fun featureId(seatIndex: Int, mode: SeatMode): Int {
        val seat = SEATS[seatIndex]
        return when (mode) {
            SeatMode.COOL -> seat.coolFeatureId
            SeatMode.HEAT -> seat.heatFeatureId
        }
    }

    /** Cấp-độ-người-dùng (0/1/2) → giá trị HAL ([HAL_VALUE]). Clamp ngoài dải để không ném (degrade-safe). */
    fun halValue(level: Int): Int = HAL_VALUE[level.coerceIn(0, HAL_VALUE.size - 1)]

    /**
     * Danh sách chỉ-số ghế theo mẫu xe. Seal (2 ghế trước) → `[0, 1]`; Han (4 ghế) → `[0, 1, 2, 3]`.
     * Đây là NGUỒN SỰ THẬT cho "hiện 2 hay 4 ghế" ở UI và "áp cho ghế nào" ở applier.
     */
    fun seatsForModel(isHan: Boolean): List<Int> = if (isHan) listOf(0, 1, 2, 3) else listOf(0, 1)

    /** Số ghế theo mẫu xe (tiện cho UI/detect): Seal=2, Han=4. */
    fun seatCountForModel(isHan: Boolean): Int = seatsForModel(isHan).size

    /**
     * Xe có phải Han không (thuần chuỗi, không phân biệt hoa/thường): tên mẫu chứa "han". Null/rỗng → false
     * (mặc định Seal — 2 ghế) để off-car / mẫu-không-rõ luôn an toàn về phía ÍT ghế hơn.
     */
    fun isHanModel(model: String?): Boolean = model != null && model.contains("han", ignoreCase = true)
}
