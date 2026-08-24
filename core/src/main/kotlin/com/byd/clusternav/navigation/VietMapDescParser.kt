package com.byd.clusternav.navigation

/**
 * Đọc dữ liệu dẫn đường của **VietMap Live** từ `contentDescription` của cây a11y — THUẦN, không Android.
 *
 * ── VÌ SAO PHẢI ĐỌC CONTENT-DESC (đo trên emulator, VietMap Live 3.3.4 ĐANG DẪN, 2026-08-22/23) ────────────
 * VietMap là app **Flutter** ⇒ KHÔNG có `resource-id`: probe bộ view-id kiểu OpenBYD
 * (`NavAccessibilityService.probeOpenBydViewIds` — `navBarDistance`/`navBarStreetLine`/…) trả **rỗng** trên
 * mọi cửa sổ của nó. Nhưng nó có phơi `contentDescription`, đọc được bằng chính a11y service của ta.
 *
 * ⚠ KHÔNG dùng `uiautomator dump` để đo lại: VietMap vẽ bản đồ liên tục nên UI **không bao giờ idle**, dump
 * báo `could not get idle state`. Muốn đo lại thì đọc qua a11y service (xem CLAUDE.md §11/§15).
 *
 * ── BA NODE ĐO ĐƯỢC (mỗi node MỘT chuỗi; `\n` là xuống dòng THẬT bên trong chuỗi đó) ───────────────────────
 * ```
 *   "Sau đó (195m)\n0m Lý Thường Kiệt"     ← (a) [maneuver kế-kế-tiếp] + CỰ LY + TÊN ĐƯỜNG hiện tại
 *   "50m Lê Lai"                           ←     cùng node (a) khi KHÔNG có dòng "Sau đó"
 *   "0\nkm/h\n50"                          ← (b) tốc độ hiện tại · "km/h" · giới hạn
 *   "00:04\n2p\n710m\nNhà thờ Hàm Long"    ← (c) giờ tới · số phút còn lại · quãng còn lại · đích
 * ```
 *
 * ── LUẬT (degrade-safe, CLAUDE.md §13) ────────────────────────────────────────────────────────────────────
 *  • **Không parse được ⇒ null**, TUYỆT ĐỐI không đoán. Một cự ly/hướng bịa trên xe đang chạy tệ hơn hẳn
 *    một ô trống.
 *  • [parse] chỉ trả khác null khi đọc được **node (a)** — tức có cự ly tới điểm rẽ. Không có nó thì app
 *    không đang dẫn (hoặc ta đọc trượt) ⇒ im lặng. Đây cũng chính là thứ khiến nhánh này **tự đóng cổng**:
 *    một cây a11y không mang bố cục này sẽ không bao giờ sinh ra reading.
 *  • Node (b)/(c) là TUỲ CHỌN: thiếu chúng vẫn ra reading hợp lệ (chỉ thiếu trường tương ứng = giá trị
 *    [UNKNOWN]).
 *
 * ── PHÂN LOẠI NODE THEO NỘI DUNG, KHÔNG THEO VỊ TRÍ ───────────────────────────────────────────────────────
 * Thứ tự node trong cây Flutter không có gì bảo đảm giữa hai bản/hai lần vẽ, nên [parse] **hỏi từng node**
 * "mày là loại nào" theo dấu hiệu nội dung (có dòng `km/h` ⇒ (b); dòng đầu là đồng hồ `HH:MM` ⇒ (c); có dòng
 * `<cự ly> <tên đường>` ⇒ (a)) thay vì tin vào chỉ số mảng. Thứ tự hỏi (b) → (c) → (a) là CỐ Ý: node (c)
 * chứa `710m` — nếu hỏi (a) trước thì nó bị nhận nhầm thành cự ly tới điểm rẽ.
 *
 * ── QUAN HỆ VỚI [NavParse] ────────────────────────────────────────────────────────────────────────────────
 * [NavParse.parseMeters] / [NavParse.extractArrivalClock] có ngữ nghĩa **tìm-bất-kỳ-đâu** (`find`), đúng cho
 * chuỗi notification một dòng nhưng SAI ở đây: `"Sau đó (195m)\n0m Lý Thường Kiệt"` sẽ ra 195 m thay vì 0 m.
 * Nên ở đây ta **neo** bằng regex riêng để tách đúng token, rồi giao token đó cho [NavParse] quy đổi đơn vị
 * (m/km, dấu phẩy thập phân) — dùng lại phép quy đổi đã có test, không chép công thức (CLAUDE.md §4.1).
 */
object VietMapDescParser {

    /** Không đọc được. Dùng chung quy ước -1 của [NavViewIdSource] / [NavAccessRow.NO_METERS]. */
    const val UNKNOWN = -1

    /**
     * Trần vô lý cho MỌI giá trị mét đọc được (500 km). Chuỗi rác kiểu `"999999999m X"` mà lọt xuống dưới sẽ
     * thành cự ly tới điểm rẽ trên cụm; chặn ngay tại biên parse thì tầng sau không phải đoán.
     * (Guard "hợp lý hoá theo chuỗi thời gian" là việc của [TurnDistancePlausibility], không thay được cái này.)
     */
    const val MAX_METERS = 500_000

    /** Trần vô lý cho tốc độ/giới hạn (km/h) — cao hơn mọi biển báo thật, đủ để loại chuỗi rác. */
    const val MAX_KMH = 399

    /**
     * Một lần đọc VietMap. Mọi trường số dùng [UNKNOWN] (-1) cho "không đọc được"; mọi trường chuỗi dùng "".
     *
     * ⚠ [speedKmh]/[limitKmh]/[destination]/[nextTurnMeters] hiện **CHƯA có consumer production** — chúng là
     * dữ liệu có kiểu mà node (b)/(c) mang sẵn, giữ lại để tầng trên dùng khi có nhu cầu (biển tốc độ hiện đã
     * có đường riêng ĐÃ PROVEN qua widget: `com.byd.clusternav.vietmapwidget.VietMapWidgetTextParser` — KHÔNG
     * được lặng lẽ thay nó bằng đường này). Ghi rõ ở đây để người sau khỏi tưởng chúng đã được nối dây.
     */
    data class Reading(
        /** Cự ly tới điểm rẽ HIỆN TẠI (mét). `0` là giá trị HỢP LỆ ("0m Lý Thường Kiệt" = đang tại điểm rẽ). */
        val turnMeters: Int,
        /** Tên đường/lệnh của điểm rẽ hiện tại. Bắt buộc non-blank (xem [parseTurn]). */
        val road: String,
        /** Cự ly của maneuver KẾ-KẾ TIẾP ("Sau đó (195m)"). [UNKNOWN] khi node không có dòng đó. */
        val nextTurnMeters: Int = UNKNOWN,
        val speedKmh: Int = UNKNOWN,
        val limitKmh: Int = UNKNOWN,
        /** Giờ tới nơi, chuẩn hoá "H:MM" bởi [NavParse.extractArrivalClock]. "" = không đọc được. */
        val arrivalClock: String = "",
        val routeSeconds: Int = UNKNOWN,
        val routeMeters: Int = UNKNOWN,
        val destination: String = "",
    )

    // ── regex (compile MỘT lần: hàm này chạy trong vòng enum cửa sổ 800 ms/nhịp) ───────────────────────────

    /**
     * Một token cự ly. **Phần thập phân chỉ hợp lệ với `km`** — xem lý do #1 ở [RE_TURN_LINE] (dấu chấm
     * hàng-nghìn kiểu vi-VN làm `"1.200 m"` ra 1 m). Dùng chung cho [RE_TURN_LINE] và [RE_DIST_ONLY] để hai
     * chỗ không thể lệch luật (CLAUDE.md §4.1).
     */
    private const val UNIT_DIST = """\d{1,9}(?:[.,]\d{1,3})?\s*km|\d{1,9}\s*m"""

    /**
     * `"0m Lý Thường Kiệt"` → (0, "Lý Thường Kiệt"). **NEO ĐẦU DÒNG** — đây là điểm khác then chốt so với
     * [NavParse.parseMeters]: dòng "Sau đó (195m)" phải TRƯỢT, không được nuốt thành cự ly hiện tại.
     *
     * HAI CHỖ THẮT so với bản 08-23 vòng 1 (cả hai biến một SỐ SAI thành IM LẶNG, không nới thêm gì):
     *
     *  1. **Phần thập phân CHỈ hợp lệ với `km`** ([UNIT_DIST]). Bản cũ `\d{1,9}(?:[.,]\d{1,3})?\s*(?:km|m)`
     *     chấp nhận `"1.200 m"` — mà `vi-VN` dùng dấu CHẤM làm **nhóm hàng nghìn**, còn
     *     [NavParse.parseMeters] luôn coi `.` là dấu THẬP PHÂN ([NavParse.kt] `RE_METERS` →
     *     `replace(",", ".").toDouble()`). Đo thật (probe 08-23): `parseTurn("1.200 m Lê Lai")` → **1 m**.
     *     Cụm sẽ báo "rẽ NGAY" khi điểm rẽ còn 1,2 km — không tầng nào chặn được vì đó là một con số hợp lệ,
     *     đang giảm, có tên đường ([TurnDistancePlausibility] tự nói rõ giới hạn này). Mét lẻ vô nghĩa trên
     *     banner dẫn đường ⇒ cấm hẳn phần thập phân cho `m`. `"1,2 km"` / `"1.2 km"` vẫn ra 1200 như cũ.
     *  2. **Tên đường phải BẮT ĐẦU bằng chữ hoặc số**. Bản cũ `(\S.*)` chỉ đòi "không-trắng": đo thật
     *     `parseTurn("710m · Nhà thờ Hàm Long")` → Turn(710, "· Nhà thờ Hàm Long") — tức một hàng POI/kết quả
     *     tìm kiếm dạng `<cự ly> · <tên>` bị nhận nhầm thành lệnh rẽ. Mọi chuỗi ĐÃ ĐO ("Lý Thường Kiệt",
     *     "Lê Lai") đều bắt đầu bằng chữ; số được giữ cho tên kiểu quốc lộ ("1A").
     */
    private val RE_TURN_LINE = Regex("""^($UNIT_DIST)\b\s*([\p{L}\p{N}].*)$""", RegexOption.IGNORE_CASE)

    /** `"Sau đó (195m)"` → token "195m". Ngoặc là dấu hiệu bắt buộc (đo 08-22); không có ⇒ không có next. */
    private val RE_NEXT = Regex("""sau\s*đó\s*\(\s*([^)]*?)\s*\)""", RegexOption.IGNORE_CASE)

    /** Cả dòng CHỈ là một cự ly (`"710m"`, `"1,2 km"`). */
    private val RE_DIST_ONLY = Regex("""^(?:$UNIT_DIST)$""", RegexOption.IGNORE_CASE)

    /** Nhãn đơn vị tốc độ — dấu hiệu nhận dạng node (b). */
    private val SPEED_UNITS = setOf("km/h", "kmh", "km/gio", "km/giờ")

    /** Cả dòng CHỈ là đồng hồ `HH:MM` — dấu hiệu nhận dạng node (c). */
    private val RE_CLOCK_LINE = Regex("""^\d{1,2}:\d{2}$""")

    /**
     * `"2p"` → 120 s. Dạng `Np` là dạng ĐÃ ĐO (08-22). Nhánh giờ (`1h20p` / `1g20p`) là **phòng xa, CHƯA đo**
     * — nó chỉ thêm ca nhận dạng, không đổi kết quả của dạng đã đo; không khớp ⇒ [UNKNOWN] (không đoán).
     */
    private val RE_MINUTES = Regex(
        """^(?:(\d{1,2})\s*(?:h|g|giờ)\s*)?(\d{1,3})\s*(?:ph|phút|min|p)$""",
        RegexOption.IGNORE_CASE,
    )

    private val RE_INT = Regex("""^\d{1,3}$""")

    /**
     * Gộp các `contentDescription` của MỘT cửa sổ VietMap thành một [Reading].
     *
     * ── FAIL-CLOSED KHI MƠ HỒ (sửa 08-23 vòng 2) ─────────────────────────────────────────────────────────
     * Bản trước lấy node điểm-rẽ ĐẦU TIÊN "đúng hình dạng" và bỏ qua phần còn lại. Đo thật (probe 08-23):
     * ```
     * parse(["1,2 km Bệnh viện Bạch Mai", "Sau đó (195m)\n0m Lý Thường Kiệt"])
     *   → Reading(turnMeters=1200, road="Bệnh viện Bạch Mai")     ← node THẬT (0 m) bị VỨT
     * ```
     * Tức một hàng gợi ý/điểm-đến vô tình đứng trước banner là ta báo SAI cự ly, không phải im lặng. Chuỗi
     * này không có neo nào khác để phân định ai đúng (Flutter không cho ta toạ độ ở tầng này), nên khi có
     * **≥ 2 ứng viên MÂU THUẪN** thì trạng thái đúng là **"chưa biết" ⇒ null** (CLAUDE.md §2/§13) — lấy cái
     * đầu chính là đoán. Bố cục ĐÃ ĐO chỉ sinh ĐÚNG MỘT node (a) nên nhánh này không đổi hành vi đã đo.
     *
     * ── KHOÁ PHÂN ĐỊNH = NỘI DUNG QUYẾT ĐỊNH, KHÔNG PHẢI CẢ [Turn] (sửa 08-23 vòng 2b) ───────────────────
     * Bản đầu của chính lần sửa này gom ứng viên bằng `LinkedHashSet<Turn>`, mà [Turn] còn mang [Turn.nextMeters]
     * — trường HIỂN THỊ PHỤ ("Sau đó (…)"), không tham gia quyết định nào. Hệ quả đo thật (probe 08-23):
     * ```
     * parseTurn("Sau đó (195m)\n0m Lý Thường Kiệt") → Turn(0, "Lý Thường Kiệt", nextMeters=195)
     * parseTurn("0m Lý Thường Kiệt")                → Turn(0, "Lý Thường Kiệt", nextMeters=-1)
     * parse([cả hai])                               → null          ← CÂM, dù hai node NÓI CÙNG MỘT ĐIỀU
     * ```
     * Đó đúng là hình dạng thường gặp của cây Flutter (node cha gộp hai dòng, node con chỉ có dòng rẽ) — tức
     * cổng fail-closed bắn vào chính bố cục THẬT, làm VietMap im hẳn mà không có tín hiệu gì phân biệt với
     * "không đang dẫn". Nó cũng trái đúng câu mà KDoc bản đầu tự tuyên bố ("node cha lặp lại node con vẫn
     * tính là MỘT").
     *
     * Nên khoá gom là **`(meters, road)`** — đúng hai trường đi tiếp vào `NavAccessibilityService.holderTurnMeters`
     * → [NavSourceDwell] R5 và ô cự ly trên cụm. Hai node KHÁC NHAU ở [Turn.nextMeters] thì KHÔNG mâu thuẫn:
     * lấy node GIÀU hơn (biết "Sau đó"). Chỉ khi hai node biết `nextMeters` mà lệch nhau mới bỏ riêng trường
     * phụ đó về [UNKNOWN] — vẫn im lặng ở đúng chỗ thiếu bằng chứng, không kéo theo cả quyết định.
     *
     * @return null khi KHÔNG tìm được node (a) (cự ly + tên đường), hoặc khi tìm được nhiều node (a) mâu thuẫn.
     */
    fun parse(descriptions: List<String?>): Reading? {
        // LinkedHashMap để giữ thứ tự gặp (tất định) — khoá = nội dung QUYẾT ĐỊNH, xem KDoc.
        val turns = LinkedHashMap<Pair<Int, String>, Turn>(2)
        var speed: Speed? = null
        var eta: Eta? = null
        for (raw in descriptions) {
            val d = raw?.takeIf { it.isNotBlank() } ?: continue
            // Thứ tự hỏi (b) → (c) → (a): node (c) chứa "710m" nên phải nhận dạng nó TRƯỚC khi thử luật (a).
            // (Viết bằng if/continue chứ không phải `?.let { … continue }`: Kotlin cấm continue trong lambda.)
            if (speed == null) {
                val s = parseSpeed(d)
                if (s != null) { speed = s; continue }
            }
            if (eta == null) {
                val e = parseEta(d)
                if (e != null) { eta = e; continue }
            }
            parseTurn(d)?.let { t ->
                val key = t.meters to t.road
                val old = turns[key]
                turns[key] = when {
                    old == null -> t
                    old.nextMeters == t.nextMeters -> old
                    old.nextMeters == UNKNOWN -> t                      // node GIÀU hơn thắng
                    t.nextMeters == UNKNOWN -> old
                    else -> old.copy(nextMeters = UNKNOWN)              // lệch ở trường phụ ⇒ bỏ RIÊNG trường phụ
                }
            }
        }
        // 0 ứng viên = không có bằng chứng đang dẫn; ≥2 ứng viên MÂU THUẪN = mơ hồ. Cả hai đều IM LẶNG.
        val t = turns.values.singleOrNull() ?: return null
        return Reading(
            turnMeters = t.meters,
            road = t.road,
            nextTurnMeters = t.nextMeters,
            speedKmh = speed?.speedKmh ?: UNKNOWN,
            limitKmh = speed?.limitKmh ?: UNKNOWN,
            arrivalClock = eta?.arrivalClock.orEmpty(),
            routeSeconds = eta?.routeSeconds ?: UNKNOWN,
            routeMeters = eta?.routeMeters ?: UNKNOWN,
            destination = eta?.destination.orEmpty(),
        )
    }

    /** Kết quả node (a). */
    data class Turn(val meters: Int, val road: String, val nextMeters: Int = UNKNOWN)

    /** Kết quả node (b). */
    data class Speed(val speedKmh: Int, val limitKmh: Int)

    /** Kết quả node (c). */
    data class Eta(
        val arrivalClock: String,
        val routeSeconds: Int,
        val routeMeters: Int,
        val destination: String,
    )

    /**
     * Node (a): `"Sau đó (195m)\n0m Lý Thường Kiệt"` hoặc `"50m Lê Lai"`.
     *
     * Lấy dòng khớp CUỐI CÙNG làm điểm rẽ hiện tại: theo bố cục đã đo, dòng "Sau đó (…)" đứng TRƯỚC dòng cự
     * ly hiện tại. (Dòng "Sau đó" tự nó không khớp [RE_TURN_LINE] vì regex neo đầu dòng, nên đây là chốt thứ
     * hai chứ không phải chốt duy nhất.)
     */
    fun parseTurn(desc: String?): Turn? {
        val lines = linesOf(desc) ?: return null
        var meters = UNKNOWN
        var road = ""
        for (line in lines) {
            val m = RE_TURN_LINE.matchEntire(line) ?: continue
            val v = metersOf(m.groupValues[1])
            if (v == UNKNOWN) continue
            meters = v
            road = m.groupValues[2].trim()
        }
        if (meters == UNKNOWN || road.isEmpty()) return null
        // "Sau đó" có thể nằm ở BẤT KỲ dòng nào của node — tìm trên nguyên chuỗi.
        val next = RE_NEXT.find(desc.orEmpty())?.groupValues?.get(1)?.let { metersOf(it) } ?: UNKNOWN
        return Turn(meters, road, next)
    }

    /**
     * Node (b): `"0\nkm/h\n50"` → tốc độ hiện tại 0, giới hạn 50.
     *
     * Neo vào dòng nhãn đơn vị: số NGAY TRƯỚC = tốc độ, số NGAY SAU = giới hạn. Thiếu bên nào ⇒ [UNKNOWN]
     * bên đó (VietMap không hiện giới hạn ở đoạn đường chưa có biển).
     */
    fun parseSpeed(desc: String?): Speed? {
        val lines = linesOf(desc) ?: return null
        val i = lines.indexOfFirst { it.lowercase() in SPEED_UNITS }
        if (i < 0) return null
        return Speed(
            speedKmh = kmhOf(lines.getOrNull(i - 1)),
            limitKmh = kmhOf(lines.getOrNull(i + 1)),
        )
    }

    /**
     * Node (c): `"00:04\n2p\n710m\nNhà thờ Hàm Long"`.
     *
     * Dấu hiệu nhận dạng = dòng ĐẦU là đồng hồ `HH:MM` (giờ tới nơi). Ba dòng sau nhận dạng theo NỘI DUNG chứ
     * không theo vị trí, để một bố cục thiếu dòng (vd không có đích) không làm lệch hết các trường còn lại.
     */
    fun parseEta(desc: String?): Eta? {
        val lines = linesOf(desc) ?: return null
        val head = lines.firstOrNull() ?: return null
        if (!RE_CLOCK_LINE.matches(head)) return null
        // Chuẩn hoá + KIỂM TRA miền (h 0..23, m 0..59) bằng hàm đã có test: "25:99" bị loại tại đây.
        val clock = NavParse.extractArrivalClock(head) ?: return null
        var seconds = UNKNOWN
        var meters = UNKNOWN
        var dest = ""
        for (line in lines.drop(1)) {
            when {
                seconds == UNKNOWN && RE_MINUTES.matches(line) -> seconds = secondsOf(line)
                meters == UNKNOWN && RE_DIST_ONLY.matches(line) -> meters = metersOf(line)
                dest.isEmpty() -> dest = line
            }
        }
        return Eta(clock, seconds, meters, dest)
    }

    /** Tách dòng + trim + bỏ dòng rỗng. null khi chuỗi rỗng/toàn khoảng trắng. */
    private fun linesOf(desc: String?): List<String>? {
        val lines = desc?.split('\n', '\r')?.map { it.trim() }?.filter { it.isNotEmpty() }
        return lines?.takeIf { it.isNotEmpty() }
    }

    /**
     * Token cự ly (`"195m"`, `"1,2 km"`) → mét. Quy đổi đơn vị + dấu phẩy thập phân giao cho
     * [NavParse.parseMeters] (đã có test); ở đây chỉ chốt "cả token phải là một cự ly" và trần [MAX_METERS].
     */
    private fun metersOf(token: String): Int {
        val t = token.trim()
        if (!RE_DIST_ONLY.matches(t)) return UNKNOWN
        val m = NavParse.parseMeters(t)
        return if (m in 0..MAX_METERS) m else UNKNOWN
    }

    private fun kmhOf(line: String?): Int {
        val t = line?.trim() ?: return UNKNOWN
        if (!RE_INT.matches(t)) return UNKNOWN
        val v = t.toIntOrNull() ?: return UNKNOWN
        return if (v in 0..MAX_KMH) v else UNKNOWN
    }

    private fun secondsOf(line: String): Int {
        val m = RE_MINUTES.matchEntire(line.trim()) ?: return UNKNOWN
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: return UNKNOWN
        return h * 3600 + min * 60
    }
}
