package com.byd.clusternav.navigation

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B3.6 (spec `waze-vietmap-screen-capture.html` §4.5): khoá off-car cơ CHẾ nạp template mũi tên Waze/VietMap.
 *
 * [ManeuverRegistry].RAW (38 mục GMaps) KHÔNG khớp glyph Waze (Hamming>18, NCC<0.45) → `classify*` trả null dù
 * crop đúng. Test này chứng minh cơ chế (KHÔNG phải dữ liệu thật): (1) [ManeuverSignature.signatureBits] rút
 * chuỗi 225-bit ĐÚNG format RAW; (2) nạp chuỗi đó vào [WazeArrowRegistry] rồi `classifyWazeInk` CHÍNH khung
 * ⇒ khớp (Hamming=0); (3) registry mực nạp hay rỗng thì 38 mục AMAP KHÔNG đổi.
 *
 * ⚠ Template Waze THẬT thu trên emulator/xe sau (KHÔNG bịa) — ở đây chỉ dùng chữ ký TỔNG HỢP để khoá cơ chế.
 *
 * ── BỐN BẤT BIẾN AN TOÀN mà file này khoá (08-23 vòng 2) ──────────────────────────────────────────────
 *  1.  ≤ 18 bit  ⇒ CÙNG quyết định           `moi cap template trong nguong 18 …`
 *  1b. khác quyết định ⇒ ≥ 37 bit             `moi cap template KHAC khoa quyet dinh phai cach it nhat 37 bit`
 *  2.  mỗi template tự phân loại lại đúng nhãn `moi template tu phan loai lai ra dung nhan cua no`
 *  3.  registry mực KHÔNG chạm đường GMaps     `template muc KHONG BAO GIO ro ri sang classify …` +
 *                                              `khung GMaps NHIEU HINH HOC …`  ← hai test này ĐỎ trước [P0]
 * Phủ sóng THẬT (locator → matcher) KHÔNG đo ở file này — xem §(7) cuối file và
 * `com.byd.clusternav.navigation.screencapture.VietMapGlyphGateTest`.
 */
class WazeArrowRegistryTest {

    @AfterEach
    fun tearDown() {
        WazeArrowRegistry.clear()   // tránh rò template tổng hợp sang test khác (registry là object toàn cục)
    }

    /** Khung trắng-đục nơi [ink], trong suốt nơi còn lại — cùng khuôn fixture ManeuverSignatureTest. */
    private fun frame(w: Int, h: Int, ink: (Int, Int) -> Boolean): PixelFrame =
        ArrayPixelFrame(w, h, IntArray(w * h) { i ->
            if (ink(i % w, i / w)) 0xFFFFFFFF.toInt() else 0x00000000
        })

    /** Một glyph tổng hợp "kiểu Waze" (khối chéo dày) KHÁC 38 template GMaps — để không trùng d==0 GMaps. */
    private fun wazeLikeGlyph(): PixelFrame = frame(60, 60) { x, y ->
        (x in 10..50 && y in 26..34) || (kotlin.math.abs(x - y) <= 3 && x in 8..52)
    }

    // ── (1) signatureBits ──────────────────────────────────────────────────────────────

    @Test
    fun `signatureBits — dung 225 ky tu chi 0 va 1`() {
        val bits = ManeuverSignature.signatureBits(wazeLikeGlyph())
        assertNotNull(bits)
        assertEquals(225, bits!!.length)
        assertTrue(bits.all { it == '0' || it == '1' })
    }

    @Test
    fun `signatureBits — tat dinh (cung khung cung chuoi)`() {
        val f = wazeLikeGlyph()
        assertEquals(ManeuverSignature.signatureBits(f), ManeuverSignature.signatureBits(f))
    }

    @Test
    fun `signatureBits — anh qua nho tra null (cung guard classify)`() {
        assertNull(ManeuverSignature.signatureBits(frame(4, 4) { _, _ -> true }))
    }

    @Test
    fun `signatureBits — khung trong (khong tuong phan) tra null`() {
        assertNull(ManeuverSignature.signatureBits(frame(30, 30) { _, _ -> false }))
    }

    // ── (2) nạp template mực → classifyWazeInk khớp (Hamming=0) ───────────────────────────

    /**
     * ⚠ Ba test dưới đây dùng [ManeuverSignature.classifyWazeInk], KHÔNG dùng `classify`/`classifyHal`
     * /`classifyManeuver`. Đổi 08-23 vòng 2 ([P0]): `match`/`matchNCC` — lõi của ba hàm kia — đã THÔI quét
     * [WazeArrowRegistry]; template mực giờ CHỈ tới được matcher qua `classifyWazeInk`. Bản cũ của mấy test
     * này chấm bằng `classify`, tức chúng đang KHOÁ ĐÚNG cái lai quy ước mà `ManeuverSignature.match` KDoc
     * cấm — xanh, nhưng khoá nhầm thứ. Xem `khung GMaps NHIEU HINH HOC…` để biết cái giá thật.
     */
    @Test
    fun `nap template muc roi classifyWazeInk CHINH khung ra ma AMAP cua ten do`() {
        val glyph = wazeLikeGlyph()
        val bits = ManeuverSignature.signatureBits(glyph)!!
        WazeArrowRegistry.register(bits, "maneuver_turn_normal_right")
        val m = ManeuverSignature.classifyWazeInk(glyph)
        assertEquals(3, m.amap)
        assertEquals(2, m.hal)                                   // HAL: 2 = rẽ phải
    }

    @Test
    fun `nap template muc ten vong xuyen roi classifyWazeInk ra Maneuver co huong`() {
        val glyph = wazeLikeGlyph()
        val bits = ManeuverSignature.signatureBits(glyph)!!
        WazeArrowRegistry.register(bits, "maneuver_roundabout_enter_and_exit_ccw_normal_left")
        assertEquals(Maneuver.ROUNDABOUT_LEFT, ManeuverSignature.classifyWazeInk(glyph).maneuver)
    }

    @Test
    fun `template muc them runtime duoc nhan ngay (version bump, khong ket dinh lazy)`() {
        val glyph = wazeLikeGlyph()
        val bits = ManeuverSignature.signatureBits(glyph)!!
        WazeArrowRegistry.register(bits, "maneuver_turn_normal_left")
        // thấy NGAY sau register (không kẹt cache lazy đóng-gói)
        assertEquals(2, ManeuverSignature.classifyWazeInk(glyph).amap)
        // Nạp lại dưới tên khác → cache đóng-gói-lại theo version → mã đổi theo.
        WazeArrowRegistry.load(listOf(bits to "maneuver_turn_normal_right"))
        assertEquals(3, ManeuverSignature.classifyWazeInk(glyph).amap)
    }

    /**
     * MẶT KIA CỦA [P0], khoá bằng CƠ CHẾ chứ không bằng số đo: một template mực nạp runtime — kể cả khớp
     * khung ĐÚNG TỪNG BIT (Hamming = 0) — vẫn KHÔNG được lọt vào `classify`/`classifyHal`/`classifyManeuver`,
     * vì ba hàm đó phục vụ đường large-icon notification GMaps (quy ước "khung vẽ", CLAUDE.md §6).
     *
     * Test này ĐỎ trước bản vá 08-23 vòng 2 (khi đó `match` quét chung hai registry và short-circuit d==0 làm
     * template mực thắng ngay). Nó là chốt rẻ nhất chặn việc gộp lại hai matcher.
     */
    @Test
    fun `template muc KHONG BAO GIO ro ri sang classify — hai quy uoc hai matcher`() {
        val glyph = wazeLikeGlyph()
        val bits = ManeuverSignature.signatureBits(glyph)!!
        // Nhãn KHÔNG có trong ManeuverRegistry ⇒ nếu nó xuất hiện ở đầu ra của `classifyDetailed` thì chỉ có
        // thể vì registry mực đã chen vào đường khung-vẽ. So bằng TÊN (không bằng mã) để phép đo là tất định:
        // nhiều tên GMaps cùng ra một mã, so mã sẽ nuốt mất chính ca cần bắt.
        val inkOnly = "maneuver_probe_chi_co_ben_muc"
        assertTrue(ManeuverRegistry.RAW.none { it.second == inkOnly }, "nhãn thử phải KHÔNG có bên GMaps")
        WazeArrowRegistry.load(listOf(bits to inkOnly))

        assertEquals(inkOnly, ManeuverSignature.classifyWazeInk(glyph).name, "đường mực phải thấy template vừa nạp")
        assertNotEquals(inkOnly, ManeuverSignature.classifyDetailed(glyph).name, "registry mực chen vào đường khung-vẽ")
    }

    // ── (3) registry: hợp lệ hoá + isolation ─────────────────────────────────────────────

    @Test
    fun `register — bo qua bits sai do dai hoac ky tu la`() {
        WazeArrowRegistry.register("101", "bad_short")
        WazeArrowRegistry.register("x".repeat(225), "bad_char")
        WazeArrowRegistry.register("0".repeat(225), "")          // tên rỗng
        assertEquals(WazeArrowRegistry.BUILTIN.size, WazeArrowRegistry.size())   // 0 mục hợp lệ thêm → giữ baseline BUILTIN
    }

    @Test
    fun `register — trung y het khong them lan hai`() {
        val bits = "0".repeat(224) + "1"
        WazeArrowRegistry.register(bits, "x")
        WazeArrowRegistry.register(bits, "x")
        assertEquals(WazeArrowRegistry.BUILTIN.size + 1, WazeArrowRegistry.size())
    }

    @Test
    fun `load — thay the ca bo, bo muc khong hop le`() {
        WazeArrowRegistry.register("0".repeat(225), "seed")
        WazeArrowRegistry.load(
            listOf(
                ("1".repeat(225)) to "ok",
                "short" to "bad",
            ),
        )
        assertEquals(1, WazeArrowRegistry.size())
        assertEquals("ok", WazeArrowRegistry.raw().single().second)
    }

    @Test
    fun `BUILTIN chi chua template hop le (thu that, khong bia)`() {
        // BUILTIN giờ có template glyph Waze THẬT (thu trên emulator qua signatureBits, KHÔNG bịa số).
        // Mỗi mục phải đúng 225-bit '0/1' + tên non-blank (dùng từ vựng ManeuverRegistry).
        WazeArrowRegistry.BUILTIN.forEach { (bits, name) ->
            assertEquals(225, bits.length)
            assertTrue(bits.all { it == '0' || it == '1' })
            assertTrue(name.isNotBlank())
        }
    }

    // ── (4) 38 mục AMAP KHÔNG đổi khi registry Waze rỗng ──────────────────────────────────

    // ── (5) classifyWazeInk KHÔNG ĐƯỢC ĐOÁN HƯỚNG (khoá hồi quy [P1] 08-22 vòng 1) ────────

    /**
     * KHOÁ: [ManeuverSignature.classifyWazeInk] chỉ được khớp bằng HAMMING (≤ 18 bit). Nhánh NCC mềm
     * (ngưỡng 0.45) đã bị GỠ và không được đưa lại.
     *
     * VÌ SAO: registry ink KHÔNG có lớp "không biết", trong khi
     * `ScreenCaptureNavSource.handleArrowByGlyph` chạy cho MỌI app (không lọc package). Một glyph mũi tên bất
     * kỳ của VietMap/GMaps lọt qua `NavGlyphLocator` sẽ tương quan ≥ 0.45 với MỘT template nào đó ⇒ kết quả
     * là bốc thăm giữa các hướng trên cụm của một chiếc xe đang chạy. Degrade-safe: không đủ bằng chứng thì
     * IM LẶNG (rơi xuống đường rect cố định), tuyệt đối không đoán hướng.
     * (Bản 08-22 của KDoc này viết "chỉ có HAI lớp" — đúng lúc đó, sai từ 08-23 khi nạp bộ VietMap ~16 lớp.
     *  Lập luận không đổi, chỉ con số lớp đổi: càng nhiều lớp thì nhánh mềm càng dễ bốc nhầm.)
     *
     * Khung thử dựng từ template RẼ TRÁI rồi BẬT 40 ô vốn tắt ở CẢ HAI lớp ⇒ Hamming ≥ 40 tới cả hai (vượt
     * ngưỡng 18) nhưng 185/225 ô vẫn trùng template trái ⇒ NCC còn rất cao. Đúng khung mà nhánh mềm cũ sẽ
     * "quyết" ra một hướng.
     */
    @Test
    fun `classifyWazeInk KHONG doan huong bang NCC khi Hamming truot`() {
        val left = WazeArrowRegistry.BUILTIN.first { it.second == "maneuver_turn_normal_left" }.first
        val right = WazeArrowRegistry.BUILTIN.first { it.second == "maneuver_turn_normal_right" }.first
        val noisy = left.toCharArray()
        var flipped = 0
        for (i in noisy.indices) {
            if (flipped >= 40) break
            if (left[i] == '0' && right[i] == '0') { noisy[i] = '1'; flipped++ }
        }
        assertEquals(40, flipped, "phải bật đủ 40 ô tắt-ở-cả-hai-lớp để Hamming vượt 18 với CẢ HAI template")

        val m = ManeuverSignature.classifyWazeInk(frameFromBits(String(noisy)))
        assertNull(m.amap, "Hamming trượt ⇒ KHÔNG được suy ra hướng nào (thà không hiện còn hơn sai hướng)")
        assertEquals("(không khớp)", m.name)

        // Mặt kia: template ĐÚNG vẫn khớp — gỡ NCC không làm mất khả năng nhận dạng thật.
        assertEquals("maneuver_turn_normal_left", ManeuverSignature.classifyWazeInk(frameFromBits(left)).name)
        assertEquals("maneuver_turn_normal_right", ManeuverSignature.classifyWazeInk(frameFromBits(right)).name)
    }

    // ── (5b) TEMPLATE VIETMAP — mỗi app vẽ mũi tên riêng, phải khớp ĐÚNG RẼ PHẢI ─────────────────────────

    /**
     * Chữ ký mũi tên VietMap Live 3.3.4 **thu tay** trên emulator (2026-08-23, `NavGlyphLocator` bbox mực
     * 95×87 @dpi240, ảnh chụp cùng nhịp: mũi tên phải + "0m Lý Thường Kiệt").
     *
     * ⚠ Chuỗi này KHÔNG còn nằm trong [WazeArrowRegistry.BUILTIN] từ 08-23 vòng 2: nó cách template
     * `turn_right` sinh từ asset APK đúng **1 bit**, nên đã bị THAY bằng bản APK (một glyph một template —
     * xem KDoc [WazeArrowRegistry.VIETMAP_INK] mục "ĐÃ THAY"). Giữ lại ở đây làm **khung thử runtime**: nó
     * là ảnh chụp THẬT, còn registry giờ toàn template sinh offline từ SVG — test dưới chính là phép kiểm
     * "template offline có khớp glyph runtime không".
     */
    private val VIETMAP_RIGHT =
        "000000000010000000000000011000000000000011100000011111111110001111111111111011111111111110011000000011100110000000011000110000000000000110000000000000110000000000000110000000000000110000000000000110000000000000110000000000000"

    /**
     * KHOÁ Ý ĐỊNH: khung mũi tên VietMap **chụp thật** phải phân loại ra **RẼ PHẢI** — AMAP 3 / HAL 2 /
     * [Maneuver.TURN_RIGHT] — qua template sinh OFFLINE từ SVG trong APK.
     *
     * Đây là lý do template VietMap tồn tại: hướng SAI trên cụm của một chiếc xe đang chạy nguy hiểm hơn hẳn
     * không hiện gì, nên test bắt đúng cái NHÃN chứ không chỉ "có khớp một cái gì đó". Và vì [VIETMAP_RIGHT]
     * KHÔNG còn trong registry, đây là phép đo cầu nối duy nhất giữa hai lò sinh chữ ký (ảnh chụp runtime ↔
     * asset SVG offline) — nó đỏ thì đừng đụng vào con số, hãy thu lại theo `vm_recipe.txt`.
     */
    @Test
    fun `mui ten VietMap phan loai dung RE PHAI`() {
        val f = frameFromBits(VIETMAP_RIGHT)
        assertEquals("maneuver_turn_normal_right", ManeuverSignature.classifyWazeInk(f).name)
        assertEquals(3, ManeuverSignature.classifyWazeInk(f).amap, "AMAP 3 = rẽ phải")
        assertEquals(Maneuver.TURN_RIGHT, Maneuver.fromAmapIcon(3))
    }

    /**
     * VÌ SAO VIETMAP PHẢI CÓ BỘ TEMPLATE RIÊNG (số đo, không phải cảm tính): chữ ký mũi tên VietMap cách
     * template rẽ-PHẢI của **Waze** 30/35 bit — **cùng họ nhưng vượt ngưỡng 18** ⇒ không có bộ riêng thì
     * `classifyWazeInk` trả "(không khớp)" và VietMap không có mũi tên nào. Cách rẽ-TRÁI 72/78 ⇒ khoảng cách
     * hai lớp vẫn rộng, không có nguy cơ lẫn hướng.
     *
     * So với [WazeArrowRegistry.WAZE_INK] chứ KHÔNG phải cả [WazeArrowRegistry.BUILTIN]: từ 08-23 BUILTIN có
     * 37 mục VietMap cùng nhãn `turn_normal_right`, gộp chung thì phép đo "khác app khác nét" mất nghĩa.
     *
     * Test ĐỎ nghĩa là ai đó vừa sửa/thu lại một template — đọc lại số đo trước khi đổi con số ở đây.
     */
    @Test
    fun `chu ky VietMap cach template Waze qua nguong 18 - dung ho, khac net`() {
        val toRight = WazeArrowRegistry.WAZE_INK
            .filter { it.second == "maneuver_turn_normal_right" }
            .map { hamming(VIETMAP_RIGHT, it.first) }
        val toLeft = WazeArrowRegistry.WAZE_INK
            .filter { it.second == "maneuver_turn_normal_left" }
            .map { hamming(VIETMAP_RIGHT, it.first) }
        assertEquals(listOf(30, 35), toRight.sorted(), "khoảng cách tới template rẽ-phải Waze (đo 08-23)")
        assertEquals(listOf(72, 78), toLeft.sorted(), "khoảng cách tới template rẽ-trái Waze (đo 08-23)")
        assertTrue(toRight.min() > 18, "nếu ≤ 18 thì template Waze đã đủ, bộ VietMap là thừa")
        assertTrue(toLeft.min() > toRight.max(), "lớp trái vẫn xa hơn hẳn lớp phải — không có nguy cơ lẫn hướng")
    }

    /**
     * MỘT GLYPH — MỘT TEMPLATE. Chuỗi thu tay [VIETMAP_RIGHT] đã bị GỠ khỏi registry vì cách template
     * `turn_right` sinh từ APK đúng 1 bit; để cả hai là hai bản của cùng một glyph (CLAUDE.md §9 tinh thần
     * "không hai bản cùng số hiệu"), và mỗi bản dư làm loãng bảng va chạm mà không thêm thông tin nào.
     */
    @Test
    fun `template thu tay da duoc THAY bang ban sinh tu APK, khong de hai ban`() {
        assertTrue(
            WazeArrowRegistry.BUILTIN.none { it.first == VIETMAP_RIGHT },
            "chuỗi thu tay vẫn còn trong registry — phải gỡ, bản APK đã phủ (cách nhau 1 bit)",
        )
        val nearest = WazeArrowRegistry.VIETMAP_INK.minOf { hamming(VIETMAP_RIGHT, it.first) }
        assertEquals(1, nearest, "template APK phải cách chữ ký chụp-thật đúng 1 bit (đo 08-23)")
    }

    // ── (5c) BỘ 37 TEMPLATE VIETMAP SINH TỪ APK 3.3.4 ────────────────────────────────────────────────────

    /**
     * Số mục + kích thước bộ. 93 file SVG trong `directions_white` cho 46 chữ ký phân biệt; bỏ 12
     * (3 icon không-phải-chỉ-dẫn + 2 icon minh hoạ đường + `invalid`/`invalid_left` trùng anh em sinh đôi +
     * `fork` trơn không mang hướng + `depart` trùng khoá với `straight` + 3 mục vi phạm biên liên-khoá 37 bit:
     * `depart_left`, `depart_right`, `rotary_right`) ⇒ **34** SVG-derived; **+6 fixture-derived (B3.52 08-25)**
     * ⇒ **40**. Lý do từng cái ở KDoc [WazeArrowRegistry.VIETMAP_INK].
     */
    @Test
    fun `VIETMAP_INK co 40 muc va BUILTIN = WAZE + VIETMAP`() {
        assertEquals(40, WazeArrowRegistry.VIETMAP_INK.size)
        assertEquals(7, WazeArrowRegistry.WAZE_INK.size)
        assertEquals(47, WazeArrowRegistry.BUILTIN.size)
        assertEquals(WazeArrowRegistry.WAZE_INK + WazeArrowRegistry.VIETMAP_INK, WazeArrowRegistry.BUILTIN)
    }

    /**
     * BẤT BIẾN AN TOÀN SỐ 1 (yêu cầu owner 08-23): **hai template cách nhau ≤ 18 bit PHẢI cho CÙNG quyết định**
     * — cùng AMAP, cùng mã HAL, cùng [Maneuver] (nếu có).
     *
     * VÌ SAO: 18 là `ManeuverSignature` MAX_HAMMING. Hai template trong ngưỡng đó là hai điểm mà một khung
     * thật hoàn toàn có thể rơi vào giữa; ai thắng là chuyện của vài bit nhiễu (nét mảnh rụng ở dpi thấp,
     * anti-alias, crop lệch 1 pixel). Nếu chúng cho hai hướng/hai độ gấp khác nhau thì mũi tên trên cụm của
     * một chiếc xe đang chạy trở thành **đồng xu** — đúng thứ CLAUDE.md cấm (thà im lặng còn hơn đoán).
     *
     * Đo bằng ĐƯỜNG CÔNG KHAI thật (`classify`/`classifyHal`/`classifyManeuver` trên khung dựng từ chính
     * chuỗi bit) chứ không chép lại bảng `nameTo*` vào test — chép là test tự khẳng định chính nó.
     *
     * Test ĐỎ = template vừa thêm đụng một template cũ với quyết định khác ⇒ **BỎ một trong hai**, không
     * được nới ngưỡng và không được sửa con số ở đây.
     */
    @Test
    fun `moi cap template trong nguong 18 phai cho CUNG mot quyet dinh`() {
        val all = WazeArrowRegistry.BUILTIN
        val near = ArrayList<String>()
        for (i in all.indices) for (j in i + 1 until all.size) {
            val d = hamming(all[i].first, all[j].first)
            if (d > 18) continue
            near.add("$d: ${all[i].second} <-> ${all[j].second}")
            assertEquals(
                decision(all[i].first), decision(all[j].first),
                "hai template cách $d bit (≤ 18) cho quyết định KHÁC nhau: ${all[i].second} vs " +
                    "${all[j].second} — một khung thật rơi vào giữa sẽ ra hướng/độ gấp ngẫu nhiên. Bỏ một cái.",
            )
        }
        // 7 cặp: 2 cặp NỘI BỘ Waze (trái d240↔d160 = 14 bit, phải 1920×1080↔1920×720 = 17 bit — hai biến thể
        // dpi/kích-thước của CÙNG một maneuver, có từ 08-22) + 5 cặp trong bộ VietMap (1/2/2/11/11 bit, liệt
        // kê ở KDoc VIETMAP_INK). Số khác đi nghĩa là bộ template vừa đổi — đọc lại bảng trước khi sửa số.
        assertEquals(7, near.size, "bảng va chạm đổi so với KDoc WAZE_INK/VIETMAP_INK:\n" + near.joinToString("\n"))
        // Và KHÔNG có cặp Waze↔VietMap nào lọt ngưỡng: hai app vẽ mũi tên khác nhau đủ xa (đo 08-23).
        val cross = WazeArrowRegistry.WAZE_INK.flatMap { w ->
            WazeArrowRegistry.VIETMAP_INK.map { v -> hamming(w.first, v.first) }
        }
        assertTrue(cross.min() > 18, "template Waze và VietMap đã chạm nhau ở ${cross.min()} bit")
    }

    /**
     * BẤT BIẾN AN TOÀN SỐ 1b (thêm 08-23 vòng 2, [P1]): **mọi cặp template cho quyết định KHÁC nhau phải cách
     * ≥ 2×18 + 1 = 37 bit.**
     *
     * VÌ SAO BẤT BIẾN "≤18 ⇒ cùng khoá" LÀ CẦN NHƯNG KHÔNG ĐỦ: nó chỉ cấm hai template khác khoá nằm SÁT nhau.
     * Nhưng nội-lớp đo được TỚI 17 bit (KDoc WAZE_INK), nên hai phân phối gần chạm nhau — một khung thật lệch
     * 11 bit khỏi template ĐÚNG hoàn toàn có thể chỉ cách một template SAI 10 bit, và `matchIn` là nearest-wins
     * KHÔNG có guard nhập nhằng ⇒ template sai thắng, cụm hiện sai hướng. Biên 37 đóng hẳn khe đó bằng bất
     * đẳng thức tam giác: khung nằm trong 18 bit của template đúng thì cách mọi template khác-khoá ≥ 19 > 18.
     *
     * Đây ĐÚNG là chuẩn mà repo đã áp cho registry GMaps ở `template muc KHONG BAO GIO lot nguong Hamming cua
     * mot khung GMaps` (cùng công thức 2×MAX_HAMMING+1). Trước 08-23 vòng 2 nó không được áp cho NỘI BỘ
     * registry mực, và biên thật khi đó là **21 bit** (`fork_right` ↔ `rotary_right`, "chếch phải" vs "vòng
     * xuyến" — một bit lật là đổi ý nghĩa).
     *
     * Test ĐỎ ⇒ **BỎ một trong hai template** (degrade-safe: im lặng còn hơn đoán), KHÔNG nới con số ở đây.
     */
    @Test
    fun `moi cap template KHAC khoa quyet dinh phai cach it nhat 37 bit`() {
        val required = 2 * 18 + 1
        val all = WazeArrowRegistry.BUILTIN
        var worst = Int.MAX_VALUE
        var worstPair = ""
        for (i in all.indices) for (j in i + 1 until all.size) {
            if (decision(all[i].first) == decision(all[j].first)) continue
            val d = hamming(all[i].first, all[j].first)
            if (d < worst) { worst = d; worstPair = "${all[i].second} ↔ ${all[j].second}" }
        }
        assertTrue(
            worst >= required,
            "hai template KHÁC quyết định chỉ cách $worst bit (< $required): $worstPair — một khung lệch " +
                "vài bit rơi vào giữa sẽ ra hướng/độ gấp ngẫu nhiên. BỎ một trong hai, đừng sửa số ở đây.",
        )
        // Số đo 08-23 vòng 2 sau khi đổi nhãn fork_* và bỏ depart_left/right + rotary_right. Nó KHÔNG phải
        // ngưỡng (ngưỡng là `required` ở trên) mà là mốc để biết bộ template vừa đổi — dư 4 bit, khá sát.
        assertEquals(41, worst, "biên liên-khoá đổi so với KDoc VIETMAP_INK — đọc lại bảng va chạm")
    }

    /**
     * BẤT BIẾN AN TOÀN SỐ 2: mỗi template phải **tự phân loại lại ra chính nhãn của nó** qua
     * [ManeuverSignature.classifyWazeInk] (đường screen-capture). Bắt hai lỗi rẻ tiền mà chỉ đọc mắt không
     * thấy: chuỗi dán nhầm dòng (nhãn lệch glyph), và chuỗi quá thưa (< MIN_SIG_BITS ⇒ matcher bỏ qua ⇒
     * template chết mà không ai biết).
     */
    @Test
    fun `moi template tu phan loai lai ra dung nhan cua no`() {
        WazeArrowRegistry.BUILTIN.forEach { (bits, name) ->
            assertEquals(name, ManeuverSignature.classifyWazeInk(frameFromBits(bits)).name, "template '$name'")
        }
    }

    /**
     * Ý ĐỊNH của bộ VietMap, viết ra bằng HƯỚNG chứ không bằng chuỗi bit: một mẫu đại diện cho mỗi họ phải
     * ra đúng mã AMAP. Đây là thứ tài xế thật sự nhìn thấy trên cụm.
     *
     * Mã: 2 rẽ trái · 3 rẽ phải · 4 chếch trái · 5 chếch phải · 6 gấp trái · 7 gấp phải · 8 quay đầu trái ·
     * 9 đi thẳng · 11 vào vòng xuyến · 12 ra vòng xuyến · 15 tới đích (xem `ManeuverSignature.nameToAmap`).
     */
    @Test
    fun `bo VietMap cho dung huong tren tung ho maneuver`() {
        val want = mapOf(
            "maneuver_turn_normal_left" to 2,
            "maneuver_turn_normal_right" to 3,
            "maneuver_turn_slight_left" to 4,
            "maneuver_turn_slight_right" to 5,
            "maneuver_turn_sharp_left" to 6,
            "maneuver_turn_sharp_right" to 7,
            "maneuver_u_turn_left" to 8,
            "maneuver_straight" to 9,
            "maneuver_merge" to 9,
            "maneuver_destination" to 15,
            "maneuver_destination_left" to 15,
            "maneuver_destination_right" to 15,
            "maneuver_roundabout_enter_ccw" to 11,
            "maneuver_roundabout_exit_ccw" to 12,
            "maneuver_roundabout_enter_and_exit_ccw_normal_left" to 11,
            "maneuver_roundabout_enter_and_exit_ccw_slight_left" to 11,
            "maneuver_roundabout_enter_and_exit_ccw_slight_right" to 11,
            "maneuver_roundabout_enter_and_exit_ccw_sharp_left" to 11,
            "maneuver_roundabout_enter_and_exit_ccw_sharp_right" to 11,
            "maneuver_roundabout_enter_and_exit_ccw_straight" to 11,
            "maneuver_roundabout_enter_and_exit_ccw_u_turn" to 11,
        )
        // Mọi nhãn dùng trong bộ VietMap đều phải có mặt trong bảng trên (thêm nhãn mới = phải khai hướng).
        assertEquals(
            want.keys, WazeArrowRegistry.VIETMAP_INK.map { it.second }.toSet(),
            "có nhãn VietMap chưa khai hướng mong đợi (hoặc khai thừa)",
        )
        WazeArrowRegistry.VIETMAP_INK.forEach { (bits, name) ->
            assertEquals(want[name], ManeuverSignature.classifyWazeInk(frameFromBits(bits)).amap, "nhãn '$name'")
        }
        // Vòng xuyến: mã AMAP gộp về 11/12 nhưng đường CAN đọc [Maneuver] có hướng — kiểm cả mặt đó.
        // Qua `classifyWazeInk(...).maneuver`, KHÔNG qua `classifyManeuver` (đường khung-vẽ không còn quét
        // registry mực từ 08-23 vòng 2, [P0]). Đây chính là giá trị mà
        // `ScreenCaptureNavSource.handleArrowByGlyph` truyền cho `publishArrow`.
        val roundaboutLeft = WazeArrowRegistry.VIETMAP_INK
            .first { it.second == "maneuver_roundabout_enter_and_exit_ccw_normal_left" }.first
        assertEquals(
            Maneuver.ROUNDABOUT_LEFT,
            ManeuverSignature.classifyWazeInk(frameFromBits(roundaboutLeft)).maneuver,
        )
    }

    /** Khoảng cách Hamming giữa hai chuỗi 225-bit. */
    private fun hamming(a: String, b: String) = a.indices.count { a[it] != b[it] }

    /**
     * Bộ ba quyết định mà một chữ ký dẫn tới, đo qua đúng API công khai của ĐƯỜNG MỰC: (AMAP, HAL, Maneuver).
     * Khung dựng 1 ô = 1 pixel nên `signature()` rút lại đúng chuỗi ⇒ Hamming 0 ⇒ khớp chính template đó.
     *
     * ⚠ Dùng [ManeuverSignature.classifyWazeInk] chứ KHÔNG phải `classify`/`classifyHal`/`classifyManeuver`:
     * từ 08-23 vòng 2 ba hàm kia không còn quét registry mực (xem [P0] ở KDoc `ManeuverSignature.match`), nên
     * đo bằng chúng sẽ ra (null, null, null) cho MỌI template ⇒ bất biến "cùng khoá" thành đúng-tầm-thường và
     * mất sạch tác dụng. Vẫn là API công khai, KHÔNG chép lại bảng `nameTo*` vào test.
     */
    private fun decision(bits: String): Triple<Int?, Int?, Maneuver?> {
        val m = ManeuverSignature.classifyWazeInk(frameFromBits(bits))
        return Triple(m.amap, m.hal, m.maneuver)
    }

    /** 225-bit '0/1' → khung 15×15 (1 ô = 1 pixel): `signature()` rút lại đúng chuỗi đó (Hamming 0). */
    private fun frameFromBits(bits: String) =
        ArrayPixelFrame(15, 15, IntArray(225) { i -> if (bits[i] == '1') 0xFFFFFFFF.toInt() else 0x00000000 })

    // ── (6) CÁCH LY HAI QUY ƯỚC CROP — bảo vệ đường GMaps notification (đã proven 18/18 on-car) ──────────

    /**
     * KHOÁ BẤT BIẾN AN TOÀN: **template quy ước "bbox mực" ([WazeArrowRegistry]) không bao giờ được thắng một
     * khung large-icon GMaps quy ước "khung vẽ"** trong [ManeuverSignature.match] — nơi HAI registry được quét
     * CHUNG cho đường notification (`ManeuverSignature.kt` §match).
     *
     * VÌ SAO PHẢI CÓ TEST NÀY: [BUILTIN] được thiết kế để MỞ RỘNG — KDoc của nó mời người sau "dán thẳng vào
     * đây kèm nhãn" chữ ký đọc từ log `arrow-sig`. Một template dán vào mà nằm gần một template GMaps thì
     * đường notification GMaps — đường ĐANG CHẠY NGOÀI HIỆN TRƯỜNG (CLAUDE.md §6) — bắt đầu trả SAI HƯỚNG,
     * IM LẶNG, không test nào đỏ. Đây chính là ca "khớp chéo quy ước" mà KDoc [WazeArrowRegistry] đã đo:
     * `off_ramp_normal_left` thay vì `turn_normal_left` trên 4/9 khung.
     *
     * LẬP LUẬN (bất đẳng thức tam giác, nên đúng cho MỌI khung thật chứ không chỉ cho các khung đã thử):
     * một khung GMaps khớp đúng template T của nó có `d(khung, T) ≤ 18` (ngưỡng `ManeuverSignature.MAX_HAMMING`).
     * Nếu MỌI template mực W thoả `d(W, T) ≥ 2×18 + 1 = 37` thì `d(khung, W) ≥ 37 − 18 = 19 > 18` ⇒ W không
     * bao giờ lọt ngưỡng, chứ đừng nói thắng. Đo lại 08-23 vòng 2: khoảng cách NHỎ NHẤT thực tế giữa hai
     * registry là **39 bit** (`maneuver_merge` ↔ `maneuver_roundabout_enter_cw`) — dư 2 bit so với biên bắt
     * buộc. (Bản 08-22 ghi 40 bit ↔ `maneuver_fork_left`; cặp đó biến mất khi bộ VietMap được tỉa 08-23.)
     *
     * ⚠ Từ 08-23 vòng 2 lập luận này KHÔNG còn là hàng phòng thủ duy nhất — và cũng chưa bao giờ là hàng
     * phòng thủ ĐỦ: nó nói về HAMMING, còn `matchNCC` chấm bằng tương quan nên biên 39 bit không ràng buộc
     * được nó (xem [P0] ở `khung GMaps NHIEU HINH HOC…`). Nay `match`/`matchNCC` đã thôi quét registry mực
     * hẳn, nên hai đường cách ly bằng CẤU TRÚC. Test này giữ lại như lớp phòng thủ thứ hai.
     *
     * Test ĐỎ nghĩa là: template vừa thêm quá giống một mũi tên GMaps ⇒ **không được thêm** (thu lại ở dpi
     * khác, hoặc siết `MAX_HAMMING`), chứ KHÔNG phải sửa con số trong test cho nó xanh.
     */
    @Test
    fun `template muc KHONG BAO GIO lot nguong Hamming cua mot khung GMaps`() {
        // Phải khớp `ManeuverSignature.MAX_HAMMING` (private). Đổi ở đó thì phải đổi ở đây.
        val maxHamming = 18
        val required = 2 * maxHamming + 1

        var worst = Int.MAX_VALUE
        var worstPair = ""
        for ((inkBits, inkName) in WazeArrowRegistry.BUILTIN) {
            for ((gmapsBits, gmapsName) in ManeuverRegistry.RAW) {
                val d = inkBits.indices.count { inkBits[it] != gmapsBits[it] }
                if (d < worst) { worst = d; worstPair = "$inkName ↔ $gmapsName" }
            }
        }
        assertTrue(
            worst >= required,
            "template mực gần một template GMaps quá ($worst bit < $required): $worstPair — " +
                "khung GMaps thật (lệch ≤ $maxHamming bit với template của nó) có thể rơi vào template mực này " +
                "và ra SAI HƯỚNG trên đường notification đã proven. KHÔNG hạ ngưỡng trong test: bỏ template đó ra.",
        )
    }

    /**
     * Mặt HÀNH VI của cùng bất biến (đo trực tiếp thay vì suy luận): chấm CẢ 38 khung dựng từ chính chữ ký
     * [ManeuverRegistry] — kết quả phải GIỐNG HỆT nhau dù registry mực có nạp hay rỗng. Khác một mục = registry
     * mực đã chen vào đường GMaps.
     */
    @Test
    fun `nap registry muc KHONG doi ket qua classify cua 38 khung GMaps`() {
        fun classifyAll(): List<Int?> = ManeuverRegistry.RAW.map { (bits, _) ->
            ManeuverSignature.classify(frameFromBits(bits))
        }

        WazeArrowRegistry.load(emptyList())          // không có template mực nào
        val baseline = classifyAll()

        WazeArrowRegistry.clear()                    // clear() = quay về BUILTIN (template mực THẬT)
        assertEquals(WazeArrowRegistry.BUILTIN.size, WazeArrowRegistry.size(), "phải đang có template mực thật")
        val withInk = classifyAll()

        assertEquals(baseline, withInk, "registry mực đã đổi kết quả của đường large-icon GMaps")
    }

    /**
     * KHOÁ §6 — mặt HÀNH VI **thật** của cùng bất biến, trên khung NHIỄU.
     *
     * ⚠ ĐÍNH CHÍNH 08-23 vòng 2: test ngay trên (`nap registry muc KHONG doi ket qua…`) **không thể ĐỎ**, nên
     * nó chứng minh 0 bit thông tin về rủi ro nó tuyên bố gác. Nó dựng khung từ CHÍNH chữ ký registry ⇒
     * Hamming = 0 ⇒ `ManeuverSignature.match` trả ngay tại `matchIn(<38 GMaps>, exactWins = true)`
     * (`ManeuverSignature.kt` §match) — chưa bao giờ chạm registry mực, và TUYỆT ĐỐI không chạm nhánh NCC.
     *
     * Đường notification chấm bằng `classifyDetailed` = `match(bits) ?: matchNCC(fill)`, và CẢ HAI hàm đều
     * quét `arrayOf(<38 GMaps>, <registry mực>)`. Nhánh NCC lấy điểm CAO NHẤT trên cả hai với ngưỡng MỀM
     * `NCC_MIN = 0.45` ⇒ thêm một mục mực (nhất là lớp `_right`, lần đầu có mặt từ 08-22/23) CÓ THỂ đè người
     * thắng cũ cho một khung GMaps THẬT đã lệch quá 18 bit — đúng ca mà lập luận biên Hamming ≥ 2×18+1 không
     * nói gì tới. Test này dựng khung nhiễu ở 4 mức (10/20/40/60 bit) trên cả 38 template để **thực sự** rơi
     * xuống nhánh NCC, rồi đòi kết quả GIỐNG HỆT khi registry mực rỗng và khi nạp đủ.
     *
     * Nhiễu TẤT ĐỊNH (seed cố định theo chỉ số) — test không được lúc xanh lúc đỏ.
     *
     * ⚠ ĐÍNH CHÍNH 08-23 vòng 2: nhiễu ở đây là **lật bit ĐỘC LẬP NGẪU NHIÊN**, và đó gần như là họ nhiễu DUY
     * NHẤT không đổi được thứ hạng NCC — nó trung bình-0, rải đều 225 ô, nên hạ điểm MỌI template gần bằng
     * nhau và người thắng không đổi. Biến thiên THẬT khi icon đổi style/dpi là nhiễu **có cấu trúc không
     * gian** (dịch, dày/mỏng nét). Vì vậy test này XANH cả trước lẫn sau bản vá [P0] — nó không gác được thứ
     * nó tuyên bố gác. Giữ lại vì rẻ và phủ một họ nhiễu khác; thứ THẬT SỰ bắt được [P0] là
     * `khung GMaps NHIEU HINH HOC…` ngay dưới.
     */
    @Test
    fun `khung GMaps NHIEU — registry muc khong duoc doi ket qua (ke ca qua nhanh NCC)`() {
        fun noisy(bits: String, flips: Int, seed: Long): String {
            val idx = (0 until WazeArrowRegistry.BITS).toMutableList()
            java.util.Collections.shuffle(idx, java.util.Random(seed))
            val a = bits.toCharArray()
            for (i in 0 until flips) { val k = idx[i]; a[k] = if (a[k] == '1') '0' else '1' }
            return String(a)
        }

        // 15–18 = dải NGAY DƯỚI/TẠI ngưỡng MAX_HAMMING — đúng vùng mà một template mực mới thêm dễ
        // chen nhất (thêm theo yêu cầu owner 08-23 khi nạp 37 template VietMap).
        val flipLevels = listOf(8, 12, 15, 16, 17, 18, 20, 25, 30, 40, 55, 70)
        val cases = ArrayList<String>()
        ManeuverRegistry.RAW.forEachIndexed { i, (bits, _) ->
            flipLevels.forEachIndexed { j, flips ->
                for (seed in 0..2) cases.add(noisy(bits, flips, i * 100L + j * 3L + seed))
            }
        }

        // So bằng TÊN template đã khớp, KHÔNG bằng mã AMAP: hai tên khác nhau có thể cùng ra một mã, nên so
        // theo mã sẽ nuốt mất đúng những ca "registry mực chen vào" mà test này sinh ra để bắt.
        fun classifyAll() = cases.map { ManeuverSignature.classifyDetailed(frameFromBits(it)).name }

        WazeArrowRegistry.load(emptyList())
        val baseline = classifyAll()
        WazeArrowRegistry.clear()
        assertEquals(WazeArrowRegistry.BUILTIN.size, WazeArrowRegistry.size(), "phải đang có template mực thật")
        val withInk = classifyAll()

        // Chứng minh test ĐI QUA nhánh NCC: đếm ca mà chữ ký THẬT của khung lệch > MAX_HAMMING với MỌI mục
        // GMaps (⇒ `match` trả null ⇒ chỉ `matchNCC` mới ra được tên) nhưng vẫn phân loại ra kết quả.
        var viaNcc = 0
        cases.forEachIndexed { i, q ->
            val sig = ManeuverSignature.signatureBits(frameFromBits(q)) ?: return@forEachIndexed
            val minD = ManeuverRegistry.RAW.minOf { (b, _) -> sig.indices.count { sig[it] != b[it] } }
            if (minD > 18 && baseline[i] != "(không khớp)" && baseline[i] != "(mờ)") viaNcc++
        }
        assertTrue(viaNcc > 0, "test không chạm được nhánh NCC ⇒ nó không gác được thứ nó nói là gác")

        assertEquals(
            baseline, withInk,
            "registry mực đã đổi kết quả phân loại của khung GMaps nhiễu ($viaNcc ca đi qua NCC) — đường " +
                "notification GMaps đang chạy ngoài hiện trường, CLAUDE.md §6 cấm đổi hành vi của nó.",
        )
    }

    /**
     * KHOÁ HỒI QUY [P0] 08-23 vòng 2 — **đây là test đã ĐỎ trước bản vá**, và là lý do bản vá tồn tại.
     *
     * Trước bản vá, `ManeuverSignature.match` VÀ `matchNCC` cùng quét `arrayOf(<38 GMaps>, <registry mực>)`.
     * Lập luận an toàn duy nhất chống lưng cho việc đó là bất đẳng thức tam giác trên HAMMING (biên ≥ 37 bit,
     * khoá ở `template muc KHONG BAO GIO lot nguong…`). Nhưng `matchNCC` KHÔNG dùng Hamming — nó là tương
     * quan trên tỉ-lệ-lấp-ô với ngưỡng MỀM 0.45, nên một template cách 39 bit vẫn ghi điểm CAO HƠN người
     * thắng cũ. Đo trên nhiễu HÌNH HỌC: **33/418 khung GMaps đổi mã AMAP** khi nạp registry mực, gồm
     * vòng-xuyến → đi-thẳng, rẽ-90° → chếch, và im-lặng → có-hướng (vi phạm degrade-safe).
     *
     * VÌ SAO NHIỄU HÌNH HỌC CHỨ KHÔNG PHẢI LẬT BIT: xem đính chính ở KDoc test ngay trên. Ba phép ở đây mô
     * phỏng đúng thứ xảy ra thật khi GMaps đổi style icon / large-icon bị upscale / dpi đổi:
     *   · dịch 1 ô theo 8 hướng (crop lệch, anti-alias đẩy bbox);
     *   · dilate 4-liên-thông (nét dày lên);
     *   · dilate rồi dịch (cả hai cùng lúc).
     *
     * ⚠ MỨC BẰNG CHỨNG (CLAUDE.md §2): "đã chứng minh" cho *registry mực CÓ đổi kết quả GMaps dưới nhiễu có
     * cấu trúc, và bản vá chặn được điều đó*. Nhiễu ở đây tổng hợp trên lưới 15×15 (1 ô ≈ 4 px trên icon
     * 54×54) nên LỚN HƠN jitter thật — chưa chốt được biên độ trên xe. Cách chốt: chạy lại trên large-icon
     * GMaps THẬT render ở 4 dpi.
     */
    @Test
    fun `khung GMaps NHIEU HINH HOC — registry muc khong duoc doi ket qua`() {
        fun shift(bits: String, dx: Int, dy: Int): String {
            val a = CharArray(225) { '0' }
            for (y in 0 until 15) for (x in 0 until 15) {
                val sx = x - dx; val sy = y - dy
                if (sx in 0..14 && sy in 0..14) a[y * 15 + x] = bits[sy * 15 + sx]
            }
            return String(a)
        }
        fun dilate(bits: String): String {
            val a = CharArray(225) { '0' }
            for (y in 0 until 15) for (x in 0 until 15) {
                val on = bits[y * 15 + x] == '1' ||
                    (x > 0 && bits[y * 15 + x - 1] == '1') || (x < 14 && bits[y * 15 + x + 1] == '1') ||
                    (y > 0 && bits[(y - 1) * 15 + x] == '1') || (y < 14 && bits[(y + 1) * 15 + x] == '1')
                if (on) a[y * 15 + x] = '1'
            }
            return String(a)
        }

        val shifts = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
        val cases = ArrayList<String>()
        ManeuverRegistry.RAW.forEach { (bits, _) ->
            shifts.forEach { (dx, dy) -> cases.add(shift(bits, dx, dy)) }
            cases.add(dilate(bits))
            shifts.take(2).forEach { (dx, dy) -> cases.add(shift(dilate(bits), dx, dy)) }
        }

        // So bằng TÊN, không bằng mã: hai tên khác nhau có thể cùng ra một mã ⇒ so theo mã sẽ nuốt mất chính
        // những ca "registry mực chen vào" mà test này sinh ra để bắt.
        fun classifyAll() = cases.map { ManeuverSignature.classifyDetailed(frameFromBits(it)).name }

        WazeArrowRegistry.load(emptyList())
        val baseline = classifyAll()
        WazeArrowRegistry.clear()                    // clear() = quay về BUILTIN (template mực THẬT)
        assertEquals(WazeArrowRegistry.BUILTIN.size, WazeArrowRegistry.size(), "phải đang có template mực thật")
        val withInk = classifyAll()

        // Chứng minh test ĐI QUA nhánh NCC (nếu không thì nó không gác được thứ nó nói là gác).
        var viaNcc = 0
        cases.forEachIndexed { i, q ->
            val sig = ManeuverSignature.signatureBits(frameFromBits(q)) ?: return@forEachIndexed
            val minD = ManeuverRegistry.RAW.minOf { (b, _) -> sig.indices.count { sig[it] != b[it] } }
            if (minD > 18 && baseline[i] != "(không khớp)" && baseline[i] != "(mờ)") viaNcc++
        }
        assertTrue(viaNcc > 0, "test không chạm được nhánh NCC ⇒ nó không gác được thứ nó nói là gác")

        val changed = baseline.indices.count { baseline[it] != withInk[it] }
        assertEquals(
            0, changed,
            "registry mực đổi kết quả của $changed/${cases.size} khung GMaps nhiễu-hình-học ($viaNcc ca qua " +
                "NCC) — đường notification GMaps đang chạy ngoài hiện trường, CLAUDE.md §6 cấm đổi hành vi. " +
                "Số đo khi TẠM bỏ bản vá [P0] để kiểm test này có đỏ được không: 16/418 với bộ 38 template " +
                "hiện tại (33/418 với bộ 41 template trước khi tỉa — đo lần đầu 08-23 vòng 1).",
        )
    }

    // ── (7) PHỦ SÓNG THẬT — đo ở đâu? KHÔNG ở file này nữa ────────────────────────────────────────────
    //
    // Test `phu song that qua cong NavGlyphLocator` (08-23 vòng 2) đã **GỠ** ở vòng 3. Nó ước lượng phủ sóng
    // bằng *proxy lưới 15×15* của chuỗi chữ ký — mà lưới đó đã chuẩn hoá kích thước nên **không thể** nhìn
    // thấy cổng chiều cao `MAX_DP`, đúng cái cổng thật sự đang chặn. Hệ quả: nó quy thủ phạm cho `MAX_FILL`
    // và chốt con số "17/34 template", cả hai đều SAI (xem `docs/diagnostics/vietmap-glyph-gate-measurement-2026-08-23.md`).
    //
    // Thay bằng phép đo THẬT chạy chính `NavGlyphLocator` trên khung 1920×1080 ghép từ asset APK:
    //   `com.byd.clusternav.navigation.screencapture.VietMapGlyphGateTest` (:core)
    // Sửa số ở đây mà không chạy test đó = quay lại đúng lỗi vừa sửa.

    /**
     * SỐ MỤC GHI TRONG KDoc PHẢI KHỚP SỐ MỤC THẬT — khoá bài học 08-23 vòng 2 (CLAUDE.md §10).
     *
     * VÌ SAO CÓ TEST NÀY (lỗi CÓ THẬT, không phải giả định): vòng 2 tỉa 3 template vì vi phạm biên liên-khoá
     * 37 bit ([VIETMAP_INK] 37 → 34), sửa 6 chỗ KDoc mang số đo cũ — và **bỏ sót đúng 3 chỗ** vẫn ghi "37":
     * tiêu đề mục `VIETMAP LIVE 3.3.4 — 37 template`, KDoc [WazeArrowRegistry.BUILTIN] và KDoc
     * [WazeArrowRegistry.clear] ("4 Waze + 37 VietMap"). Không một test nào đỏ, vì các con số đó chỉ sống
     * trong comment. Đó đúng là họ lỗi CLAUDE.md §2 cấm: tài liệu nói một đằng, phép đo một nẻo — mà toàn bộ
     * lập luận an toàn của registry này (biên 37/41 bit, phủ sóng end-to-end) được đọc TỪ chính KDoc đó.
     *
     * Test quét **văn bản source** thay vì tin người sửa nhớ cập nhật. Mỗi mẫu PHẢI khớp ít nhất một lần:
     * nếu ai đó đổi cách diễn đạt, test ĐỎ ở nhánh "không tìm thấy" chứ KHÔNG lặng lẽ thôi gác — đúng cảnh
     * báo FAIL-OPEN ở KDoc [com.byd.clusternav.testsupport.KotlinSource].
     *
     * Test ĐỎ ⇒ sửa **con số trong KDoc** cho khớp registry (hoặc sửa registry nếu chính nó mới sai), KHÔNG
     * nới mẫu regex để né.
     */
    @Test
    fun `moi con so template ghi trong KDoc phai khop registry that`() {
        val src = com.byd.clusternav.testsupport.SourceRoots
            .text("src/main/kotlin/com/byd/clusternav/navigation/WazeArrowRegistry.kt")
        val waze = WazeArrowRegistry.WAZE_INK.size
        val vietmap = WazeArrowRegistry.VIETMAP_INK.size
        val svgKept = 34          // B3.52: bộ VietMap = 34 sinh từ SVG asset (46→34) + 6 sinh từ fixture runtime
        val fixtureAdded = 6
        assertEquals(svgKept + fixtureAdded, vietmap, "VIETMAP_INK = SVG-derived + fixture-derived phải khớp")
        // Phủ sóng end-to-end, ĐO bằng locator thật — giữ khớp `VietMapGlyphGateTest.MATCHED_TO_AMAP`
        // (63) và số khung maneuver trong fixture VietMap (87). Đổi một trong hai ⇒ test kia đỏ trước.
        val matched = 63
        val maneuverFrames = 87

        // (mô tả, regex, danh sách giá trị đúng theo thứ tự nhóm bắt)
        val claims = listOf(
            Triple("\"N Waze + M VietMap\"", Regex("""(\d+) Waze \+ (\d+) VietMap"""), listOf(waze, vietmap)),
            Triple("\"[WAZE_INK] (N mục\"", Regex("""\[WAZE_INK] \((\d+) mục"""), listOf(waze)),
            Triple("\"[VIETMAP_INK] (M mục\"", Regex("""\[VIETMAP_INK] \((\d+) mục"""), listOf(vietmap)),
            Triple("tiêu đề \"VIETMAP LIVE 3.3.4 — M template\"", Regex("""VIETMAP LIVE [\d.]+ — (\d+) template"""), listOf(vietmap)),
            // "46 → 34" mô tả lò SVG (không đổi khi thêm fixture); bắt riêng svgKept, KHÔNG phải tổng.
            Triple("\"ĐÃ BỎ … (46 → 34)\"", Regex("""ĐÃ BỎ \d+ chữ ký \(\d+ → (\d+)\)"""), listOf(svgKept)),
            // B3.52: số template fixture-derived cộng thêm ("+ 6 FIXTURE …").
            Triple("\"+ N FIXTURE\"", Regex("""\+ (\d+) FIXTURE""", RegexOption.IGNORE_CASE), listOf(fixtureAdded)),
            // 08-23 vòng 3b: KDoc nói "khung" chứ không phải "template" (63 trên 87 KHUNG maneuver đi trọn
            // đường; số template là 40). Mẫu phải bám cụm "… khung [maneuver] đi trọn" để không vồ nhầm
            // con số lịch sử "4/9 khung" ở KDoc `classifyWazeInk`.
            Triple(
                "phủ sóng \"63/87 khung … đi trọn\"",
                Regex("""(\d+)/(\d+)\**\s+khung(?:\s+maneuver)?\s+đi trọn""", RegexOption.IGNORE_CASE),
                listOf(matched, maneuverFrames),
            ),
        )

        val problems = ArrayList<String>()
        for ((what, rx, want) in claims) {
            val hits = rx.findAll(src).toList()
            if (hits.isEmpty()) {
                problems += "KHÔNG tìm thấy mẫu $what — KDoc đã đổi cách diễn đạt; sửa test cho khớp, " +
                    "đừng bỏ mẫu (bỏ = guard mù)."
                continue
            }
            hits.forEach { m ->
                val got = m.groupValues.drop(1).map(String::toInt)
                if (got != want) {
                    problems += "$what: KDoc ghi $got nhưng registry thật là $want — tại \"${m.value}\""
                }
            }
        }
        assertTrue(
            problems.isEmpty(),
            "số đo trong KDoc WazeArrowRegistry lệch với registry thật:\n" + problems.joinToString("\n"),
        )
    }

    @Test
    fun `registry Waze rong — 38 muc AMAP van classify dung (khong regression)`() {
        // Dựng khung TỪ chữ ký registry GMaps (Hamming=0) → phải ra đúng mã, không bị Waze registry chen.
        val bits = ManeuverRegistry.RAW.first { it.second == "maneuver_turn_normal_left" }.first
        val g = 15
        val f = ArrayPixelFrame(g, g, IntArray(g * g) { i -> if (bits[i] == '1') 0xFFFFFFFF.toInt() else 0x00000000 })
        assertEquals(2, ManeuverSignature.classify(f))           // 2 = rẽ trái (như ManeuverSignatureTest canary)
    }
}
