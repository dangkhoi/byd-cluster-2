package com.byd.clusternav.navigation

/**
 * Registry NẠP-ĐƯỢC của chữ ký glyph mũi tên Waze / VietMap (chuỗi 225-bit '0/1' → tên maneuver), được
 * [ManeuverSignature] khớp SONG SONG với registry dựng-sẵn 38 mục GMaps/AMAP ([ManeuverRegistry]) — B3.6, spec
 * `docs/specs/waze-vietmap-screen-capture.html` §4.5.
 *
 * VÌ SAO (B3.6): [ManeuverRegistry].RAW tự sinh từ icon GMaps/Mapbox; glyph mũi-tên-trắng-trên-nền-đen của Waze
 * KHÔNG khớp (Hamming > 18, NCC < 0.45) nên `classify*` trả null DÙ crop đúng. Registry này cho phép thêm
 * template Waze/VietMap THẬT — sinh sau này từ ảnh PNG crop trên emulator qua [ManeuverSignature.signatureBits]
 * (đúng chuỗi 225-bit như RAW) — để `classify*` cũng nhận được mũi tên Waze/VietMap.
 *
 * KHỞI TẠO RỖNG ([BUILTIN] = emptyList). Bitstring THẬT thêm trên xe/emulator sau (⚠ KHÔNG bịa số giả). Tên
 * maneuver DÙNG CÙNG từ vựng với [ManeuverRegistry] (vd `"maneuver_turn_normal_left"`) để [ManeuverSignature]
 * ánh xạ TÊN→AMAP/HAL/Maneuver y hệt — không cần bảng map riêng.
 *
 * Thread-safe: ghi (register/load/clear) `@Synchronized` + bump [version]; đọc [raw] trả snapshot bất biến
 * (list copy-on-write) để matcher đọc không khoá. [version] để [ManeuverSignature] cache dạng-đóng-gói và chỉ
 * đóng-gói-lại khi registry đổi (production rỗng ⇒ 0 chi phí).
 */
object WazeArrowRegistry {

    /** Số bit mỗi chữ ký = lưới 15×15 (khớp [ManeuverRegistry] / [ManeuverSignature]). */
    const val BITS = 225

    /**
     * Dựng-sẵn: template glyph THẬT thu trên emulator qua [ManeuverSignature.signatureBits] (crop tight
     * arrow-only @960×720). Tên dùng từ vựng [ManeuverRegistry] để ánh xạ TÊN→AMAP/HAL/Maneuver y hệt.
     * ⚠ Mở rộng dần từng maneuver (phải/thẳng/quay-đầu/vòng-xuyến…) khi thu được; xác nhận trên xe (OQ2).
     */
    val BUILTIN: List<Pair<String, String>> = listOf(
        // Waze/WazeMod rẽ TRÁI (emulator 960×720, 2026-08-20) — glyph mũi-tên-trắng khác hẳn icon GMaps nên
        // 38-mục RAW khớp NHẦM (ROUNDABOUT); template này Hamming≈0 với glyph Waze trái → thắng, cho ra TRÁI.
        "000000000000000000000000000000000000000000000000110000000000000111110000000000110011000000000000001100000000000000100000000000000100000000000000100000000000000100000000000000100000000000000000000000000000000000000000000000000" to "maneuver_turn_normal_left",
        // Waze/WazeMod rẽ TRÁI biến thể 2 (emulator 960×720, 2026-08-20; LEFT + "and then" phải phụ, 220m Nguyễn Du)
        "000000000000000000000000000000000000110000000000001011000000000010001100000000010000100000000010000100000000010000100000000010000100000000110000100000000111000100000000110000100000000000000000000000000000000000000000000000000" to "maneuver_turn_normal_left",
    )

    @Volatile private var entries: List<Pair<String, String>> = BUILTIN

    /** Tăng mỗi lần registry đổi — [ManeuverSignature] dùng để biết khi nào đóng-gói-lại cache. */
    @Volatile var version: Int = 0
        private set

    /** Snapshot bất biến (chuỗi bit, tên) cho matcher. */
    fun raw(): List<Pair<String, String>> = entries

    /** Số template hiện có (chẩn đoán / test). */
    fun size(): Int = entries.size

    /**
     * Thêm MỘT (chuỗi 225-bit, tên). Bỏ qua nếu bits không đúng 225 ký tự 0/1 hoặc tên rỗng (degrade-safe:
     * template hỏng không làm gãy matcher). Idempotent theo nội dung: cặp trùng y hệt không thêm lần hai.
     */
    @Synchronized
    fun register(bits: String, name: String) {
        if (!isValid(bits) || name.isBlank()) return
        val pair = bits to name
        if (pair in entries) return
        entries = entries + pair
        version++
    }

    /**
     * Nạp nguyên bộ (thay thế tập hiện tại) — bỏ các mục không hợp lệ. Dùng khi orchestrator có sẵn bảng
     * template thu từ emulator/xe.
     */
    @Synchronized
    fun load(templates: List<Pair<String, String>>) {
        entries = templates.filter { isValid(it.first) && it.second.isNotBlank() }
        version++
    }

    /** Xoá về [BUILTIN] (rỗng). Chủ yếu cho test để không rò template tổng hợp sang test khác. */
    @Synchronized
    fun clear() {
        entries = BUILTIN
        version++
    }

    private fun isValid(bits: String): Boolean =
        bits.length == BITS && bits.all { it == '0' || it == '1' }
}
