package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Khoá [VietMapDescParser] — đường CHỮ của VietMap Live.
 *
 * Ba chuỗi trong `SAU_DO` / `TOC_DO` / `ETA` là **số đo THẬT**, chép nguyên văn từ phiên đo trên emulator
 * (VietMap Live 3.3.4 đang dẫn, 2026-08-22/23). `\n` trong nguồn Kotlin là đúng ký tự xuống dòng có trong
 * `contentDescription` thật.
 *
 * Test này khoá hai thứ:
 *  • **Đọc đúng** bố cục đã đo (kể cả biến thể thiếu dòng "Sau đó").
 *  • **Im lặng đúng lúc**: chuỗi rác / thiếu bằng chứng ⇒ null, KHÔNG bao giờ đoán ra một cự ly. Trên xe đang
 *    chạy, một cự ly bịa nguy hiểm hơn hẳn một ô trống (CLAUDE.md §13).
 */
class VietMapDescParserTest {

    private val SAU_DO = "Sau đó (195m)\n0m Lý Thường Kiệt"
    private val TOC_DO = "0\nkm/h\n50"
    private val ETA = "00:04\n2p\n710m\nNhà thờ Hàm Long"

    // ── (1) ba node THẬT, gộp lại ────────────────────────────────────────────────────────────────────────

    @Test
    fun `ba node do that gop thanh mot reading day du`() {
        val r = VietMapDescParser.parse(listOf(SAU_DO, TOC_DO, ETA))!!
        assertEquals(0, r.turnMeters, "'0m Lý Thường Kiệt' — 0 là cự ly HỢP LỆ, không phải 'không biết'")
        assertEquals("Lý Thường Kiệt", r.road)
        assertEquals(195, r.nextTurnMeters, "'Sau đó (195m)' = maneuver kế-kế-tiếp")
        assertEquals(0, r.speedKmh)
        assertEquals(50, r.limitKmh)
        assertEquals("0:04", r.arrivalClock)
        assertEquals(120, r.routeSeconds, "'2p' = 2 phút")
        assertEquals(710, r.routeMeters)
        assertEquals("Nhà thờ Hàm Long", r.destination)
    }

    /** Thứ tự node trong cây Flutter không bảo đảm ⇒ phân loại theo NỘI DUNG, đảo thứ tự vẫn ra y hệt. */
    @Test
    fun `dao thu tu node khong doi ket qua`() {
        val a = VietMapDescParser.parse(listOf(SAU_DO, TOC_DO, ETA))
        val b = VietMapDescParser.parse(listOf(ETA, SAU_DO, TOC_DO))
        val c = VietMapDescParser.parse(listOf(TOC_DO, ETA, SAU_DO))
        assertEquals(a, b)
        assertEquals(a, c)
    }

    /**
     * BẪY CHÍNH của bố cục này: node ETA cũng chứa một cự ly (`710m`). Nếu nhận nhầm nó thành cự ly tới điểm
     * rẽ thì HUD hiện 710 m trong khi tài xế còn 0 m tới chỗ rẽ.
     */
    @Test
    fun `710m cua node ETA KHONG bao gio thanh cu ly toi diem re`() {
        val r = VietMapDescParser.parse(listOf(ETA, SAU_DO))!!
        assertEquals(0, r.turnMeters)
        assertEquals(710, r.routeMeters)
        // Và một mình node ETA thì KHÔNG đủ để nói "đang dẫn".
        assertNull(VietMapDescParser.parse(listOf(ETA)))
    }

    // ── (2) node (a): cự ly + tên đường ──────────────────────────────────────────────────────────────────

    /** Biến thể ĐÃ ĐO khi không có maneuver kế-kế-tiếp: một dòng duy nhất. */
    @Test
    fun `thieu dong Sau do van doc duoc`() {
        val t = VietMapDescParser.parseTurn("50m Lê Lai")!!
        assertEquals(50, t.meters)
        assertEquals("Lê Lai", t.road)
        assertEquals(VietMapDescParser.UNKNOWN, t.nextMeters)
    }

    @Test
    fun `don vi km va dau phay thap phan`() {
        assertEquals(1200, VietMapDescParser.parseTurn("1,2km Nguyễn Trãi")!!.meters)
        assertEquals(1200, VietMapDescParser.parseTurn("1.2 km Nguyễn Trãi")!!.meters)
        assertEquals(300, VietMapDescParser.parseTurn("300 m Nguyễn Huệ")!!.meters)
        assertEquals(1500, VietMapDescParser.parseTurn("Sau đó (1,5km)\n80m Lê Lợi")!!.nextMeters)
    }

    /** "Sau đó" mà cự ly bên trong là rác ⇒ chỉ mất trường next, KHÔNG được làm hỏng cự ly hiện tại. */
    @Test
    fun `Sau do rac chi mat truong next`() {
        val t = VietMapDescParser.parseTurn("Sau đó (--)\n80m Lê Lợi")!!
        assertEquals(80, t.meters)
        assertEquals("Lê Lợi", t.road)
        assertEquals(VietMapDescParser.UNKNOWN, t.nextMeters)
    }

    /**
     * Cự ly KHÔNG có tên đường (vd chính dòng `710m`) không đủ bằng chứng ⇒ null. Đây là chốt khiến node ETA
     * và các node lạ không bao giờ hoá thành điểm rẽ.
     */
    @Test
    fun `cu ly ma khong co ten duong thi tra null`() {
        assertNull(VietMapDescParser.parseTurn("710m"))
        assertNull(VietMapDescParser.parseTurn("1,2 km"))
    }

    @Test
    fun `chuoi rac tra null`() {
        assertNull(VietMapDescParser.parseTurn(null))
        assertNull(VietMapDescParser.parseTurn(""))
        assertNull(VietMapDescParser.parseTurn("   \n  "))
        assertNull(VietMapDescParser.parseTurn("Đang tìm đường"))
        assertNull(VietMapDescParser.parseTurn("Sau đó (195m)"), "chỉ có dòng kế-kế-tiếp = chưa có điểm rẽ")
        assertNull(VietMapDescParser.parseTurn("Lý Thường Kiệt 50m"), "cự ly phải NEO đầu dòng")
        assertNull(VietMapDescParser.parseTurn("50mLý Thường Kiệt"), "thiếu ranh giới sau đơn vị")
        assertNull(VietMapDescParser.parseTurn("50 dặm Lê Lai"), "đơn vị lạ")
    }

    /** Trần vô lý: chuỗi rác dài không được biến thành cự ly. */
    @Test
    fun `cu ly vuot tran vo ly bi loai`() {
        assertNull(VietMapDescParser.parseTurn("999999m Đường X"))
        assertNull(VietMapDescParser.parseTurn("501 km Đường X"))
        assertEquals(499_000, VietMapDescParser.parseTurn("499 km Đường X")!!.meters)
    }

    // ── (3) node (b): tốc độ + giới hạn ──────────────────────────────────────────────────────────────────

    @Test
    fun `toc do va gioi han quanh nhan km per h`() {
        val s = VietMapDescParser.parseSpeed(TOC_DO)!!
        assertEquals(0, s.speedKmh)
        assertEquals(50, s.limitKmh)
        assertEquals(62, VietMapDescParser.parseSpeed("62\nkm/h\n80")!!.speedKmh)
    }

    /** Đoạn đường chưa có biển: VietMap không hiện số giới hạn ⇒ chỉ mất trường đó. */
    @Test
    fun `thieu gioi han van doc duoc toc do`() {
        val s = VietMapDescParser.parseSpeed("35\nkm/h")!!
        assertEquals(35, s.speedKmh)
        assertEquals(VietMapDescParser.UNKNOWN, s.limitKmh)
        val s2 = VietMapDescParser.parseSpeed("35\nkm/h\n--")!!
        assertEquals(VietMapDescParser.UNKNOWN, s2.limitKmh)
    }

    @Test
    fun `khong co nhan don vi thi khong phai node toc do`() {
        assertNull(VietMapDescParser.parseSpeed("0\n50"))
        assertNull(VietMapDescParser.parseSpeed(SAU_DO))
        assertNull(VietMapDescParser.parseSpeed(ETA))
        assertNull(VietMapDescParser.parseSpeed(null))
    }

    @Test
    fun `toc do vuot tran vo ly bi loai`() {
        val s = VietMapDescParser.parseSpeed("9999\nkm/h\n400")!!
        assertEquals(VietMapDescParser.UNKNOWN, s.speedKmh)
        assertEquals(VietMapDescParser.UNKNOWN, s.limitKmh)
    }

    // ── (4) node (c): ETA ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `eta day du`() {
        val e = VietMapDescParser.parseEta(ETA)!!
        assertEquals("0:04", e.arrivalClock)
        assertEquals(120, e.routeSeconds)
        assertEquals(710, e.routeMeters)
        assertEquals("Nhà thờ Hàm Long", e.destination)
    }

    @Test
    fun `eta thieu dich hoac thieu phut van doc duoc phan con lai`() {
        val e = VietMapDescParser.parseEta("18:21\n197m\nNhà")!!
        assertEquals("18:21", e.arrivalClock)
        assertEquals(VietMapDescParser.UNKNOWN, e.routeSeconds)
        assertEquals(197, e.routeMeters)
        assertEquals("Nhà", e.destination)
    }

    @Test
    fun `eta dong ho sai mien bi loai`() {
        assertNull(VietMapDescParser.parseEta("25:00\n2p\n710m\nX"))
        assertNull(VietMapDescParser.parseEta("12:70\n2p\n710m\nX"))
        assertNull(VietMapDescParser.parseEta("2p\n710m\nX"), "dòng đầu phải là đồng hồ")
        assertNull(VietMapDescParser.parseEta(null))
    }

    /** Dạng `Np` là dạng đã ĐO; `1h20p` là nhánh phòng xa — cả hai phải ra giây đúng, dạng lạ ⇒ UNKNOWN. */
    @Test
    fun `phut va gio`() {
        assertEquals(120, VietMapDescParser.parseEta("00:04\n2p\n710m\nX")!!.routeSeconds)
        assertEquals(4800, VietMapDescParser.parseEta("00:04\n1h20p\n710m\nX")!!.routeSeconds)
        assertEquals(300, VietMapDescParser.parseEta("00:04\n5 phút\n710m\nX")!!.routeSeconds)
        assertEquals(
            VietMapDescParser.UNKNOWN,
            VietMapDescParser.parseEta("00:04\n2 giây\n710m\nX")!!.routeSeconds,
        )
    }

    // ── (5) parse(): cổng "có bằng chứng đang dẫn hay không" ─────────────────────────────────────────────

    @Test
    fun `khong co node cu ly thi parse tra null`() {
        assertNull(VietMapDescParser.parse(emptyList()))
        assertNull(VietMapDescParser.parse(listOf(null, "", "   ")))
        assertNull(VietMapDescParser.parse(listOf(TOC_DO, ETA)), "có tốc độ + ETA nhưng KHÔNG có điểm rẽ")
        assertNull(VietMapDescParser.parse(listOf("Trang chủ", "Tìm kiếm", "Bắt đầu")))
    }

    /** Chỉ có node (a) là đủ để nói "đang dẫn" — các trường khác về UNKNOWN, không bịa. */
    @Test
    fun `chi mot node cu ly van ra reading, cac truong khac UNKNOWN`() {
        val r = VietMapDescParser.parse(listOf("50m Lê Lai"))!!
        assertEquals(50, r.turnMeters)
        assertEquals("Lê Lai", r.road)
        assertEquals(VietMapDescParser.UNKNOWN, r.speedKmh)
        assertEquals(VietMapDescParser.UNKNOWN, r.limitKmh)
        assertEquals(VietMapDescParser.UNKNOWN, r.routeSeconds)
        assertEquals(VietMapDescParser.UNKNOWN, r.routeMeters)
        assertEquals("", r.arrivalClock)
        assertEquals("", r.destination)
    }

    /** Node rác xen giữa không được làm hỏng ba node thật (cây a11y thật có hàng chục node). */
    @Test
    fun `node rac xen giua khong lam hong ket qua`() {
        val r = VietMapDescParser.parse(
            listOf("Bản đồ", "Nút phóng to", TOC_DO, "Lớp bản đồ", ETA, "", SAU_DO, "Kết thúc"),
        )
        assertNotNull(r)
        assertEquals(0, r!!.turnMeters)
        assertEquals("Lý Thường Kiệt", r.road)
        assertEquals(50, r.limitKmh)
        assertEquals(710, r.routeMeters)
    }

    // ── HỒI QUY 08-23 vòng 2 — ba khe cho ra SỐ SAI (không phải im lặng). Đo thật bằng probe trước khi sửa. ──

    /**
     * KHOÁ [P1]: **dấu chấm hàng-nghìn kiểu vi-VN không được biến 1,2 km thành 1 m.**
     *
     * Trước sửa (probe 08-23, chạy thật): `parseTurn("1.200 m Lê Lai")` → `Turn(meters=1, …)`. Nguyên nhân
     * kép: [VietMapDescParser] cho phép phần thập phân với đơn vị `m`, còn `NavParse.parseMeters` luôn coi `.`
     * là dấu THẬP PHÂN (`NavParse.kt` RE_METERS → `replace(",", ".").toDouble()`). Cụm sẽ báo "1 m" = RẼ NGAY
     * khi điểm rẽ còn 1,2 km — và KHÔNG tầng nào chặn được: [TurnDistancePlausibility] chỉ soi chuỗi thời gian,
     * mà một cự ly sai-nhưng-giảm-đều-có-tên-đường là chuỗi hợp lý hoàn hảo (chính KDoc lớp đó đã tự nêu giới hạn).
     *
     * Luật sau sửa: `m` KHÔNG có phần thập phân ⇒ chuỗi mơ hồ thành **null** (im lặng), còn `km` giữ nguyên
     * phần thập phân nên các ca ĐÃ ĐO không đổi một chút nào.
     */
    @Test
    fun `dau cham hang nghin voi don vi m — IM LANG chu khong ra 1m`() {
        assertNull(VietMapDescParser.parseTurn("1.200 m Lê Lai"), "1.200 m từng ra 1 m")
        assertNull(VietMapDescParser.parseTurn("1.500m Lê Lai"))
        assertNull(VietMapDescParser.parseTurn("1,200 m Lê Lai"), "dấu phẩy + đơn vị m cũng mơ hồ y hệt")
        // Mặt kia — km GIỮ NGUYÊN phần thập phân (đây là dạng VietMap thật dùng cho quãng ≥ 1 km).
        assertEquals(1200, VietMapDescParser.parseTurn("1,2 km Lê Lai")!!.meters)
        assertEquals(1200, VietMapDescParser.parseTurn("1.2 km Lê Lai")!!.meters)
        // …và mét NGUYÊN vẫn đọc bình thường.
        assertEquals(1200, VietMapDescParser.parseTurn("1200m Lê Lai")!!.meters)
        assertEquals(0, VietMapDescParser.parseTurn("0m Lý Thường Kiệt")!!.meters)
    }

    /**
     * KHOÁ [P1]: **`<cự ly> · <tên>` (hàng POI/kết quả tìm kiếm) KHÔNG được nhận nhầm thành lệnh rẽ.**
     *
     * Trước sửa: `parseTurn("710m · Nhà thờ Hàm Long")` → `Turn(710, "· Nhà thờ Hàm Long")`; `"500m ·"` cũng
     * qua. Cổng cũ `(\S.*)` chỉ đòi "một ký tự không-trắng" nên MỌI dấu câu đều đủ tư cách làm "tên đường".
     * Nay tên đường phải bắt đầu bằng CHỮ hoặc SỐ — mọi chuỗi đã đo ("Lý Thường Kiệt", "Lê Lai") không đổi,
     * và tên kiểu quốc lộ ("1A") vẫn qua.
     */
    @Test
    fun `dong POI dang cu-ly-cham-ten KHONG duoc thanh lenh re`() {
        assertNull(VietMapDescParser.parseTurn("710m · Nhà thờ Hàm Long"))
        assertNull(VietMapDescParser.parseTurn("500m ·"))
        assertNull(VietMapDescParser.parseTurn("500m -"))
        assertEquals("Lê Lai", VietMapDescParser.parseTurn("50m Lê Lai")!!.road)
        assertEquals("1A", VietMapDescParser.parseTurn("50m 1A")!!.road, "tên quốc lộ bắt đầu bằng số vẫn hợp lệ")
    }

    /**
     * KHOÁ [P1]: **hai node điểm-rẽ MÂU THUẪN ⇒ null**, không phải "lấy cái đầu tiên".
     *
     * Trước sửa (probe 08-23): `parse(["1,2 km Bệnh viện Bạch Mai", SAU_DO])` → `Reading(turnMeters=1200,
     * road="Bệnh viện Bạch Mai")` — node THẬT (0 m) bị vứt vì một hàng gợi ý tình cờ đứng trước. Ở tầng này
     * không có neo nào (toạ độ/độ sâu) để phân định ai đúng, nên "lấy cái đầu" CHÍNH LÀ đoán (CLAUDE.md §2).
     * Con số này không chỉ hiển thị: nó đi tiếp vào `NavAccessibilityService.holderTurnMeters` →
     * [NavSourceDwell] R5, tức nó bỏ phiếu cho việc KHOÁ nguồn.
     *
     * Ứng viên TRÙNG NHAU y hệt (node cha lặp lại node con) vẫn chỉ tính là MỘT — không được vì fail-closed
     * mà làm câm bố cục thật.
     */
    @Test
    fun `hai node diem-re mau thuan ⇒ IM LANG (fail-closed)`() {
        assertNull(VietMapDescParser.parse(listOf("1,2 km Bệnh viện Bạch Mai", SAU_DO)))
        assertNull(VietMapDescParser.parse(listOf(SAU_DO, "50m Lê Lai")))
        // Trùng y hệt = một ứng viên.
        val r = VietMapDescParser.parse(listOf(SAU_DO, SAU_DO, TOC_DO))
        assertNotNull(r)
        assertEquals(0, r!!.turnMeters)
        assertEquals("Lý Thường Kiệt", r.road)
    }

    /**
     * KHOÁ hồi quy 08-23 vòng 2b — **fail-closed KHÔNG được bắn vào bố cục THẬT**.
     *
     * Bản đầu của cổng fail-closed ở trên gom ứng viên bằng `LinkedHashSet<Turn>`, mà `Turn` còn mang
     * `nextMeters` (trường HIỂN THỊ phụ, không tham gia quyết định nào). Đo thật (probe 08-23):
     * ```
     * parse(["Sau đó (195m)\n0m Lý Thường Kiệt", "0m Lý Thường Kiệt"]) → null   ← CÂM
     * ```
     * Hai node NÓI CÙNG MỘT ĐIỀU (0 m, Lý Thường Kiệt) bị coi là mâu thuẫn chỉ vì một node biết thêm "Sau
     * đó" — mà node-cha-gộp-dòng + node-con-một-dòng đúng là hình dạng thường gặp của cây Flutter. Tức cổng
     * chống-đoán tự làm câm VietMap, không phân biệt được với "không đang dẫn" (CLAUDE.md §8).
     *
     * Sau sửa: khoá phân định là `(meters, road)` — đúng hai trường đi tiếp vào `holderTurnMeters` →
     * [NavSourceDwell] R5 và ô cự ly. Node GIÀU hơn thắng; mâu thuẫn THẬT vẫn im lặng (assert cuối).
     */
    @Test
    fun `node cha gop dong + node con mot dong = MOT ung vien, khong lam cam`() {
        val cha = SAU_DO                       // "Sau đó (195m)\n0m Lý Thường Kiệt"
        val con = "0m Lý Thường Kiệt"          // cùng điểm rẽ, KHÔNG có dòng "Sau đó"

        val r = VietMapDescParser.parse(listOf(cha, con))
        assertNotNull(r, "hai node đồng thuận về điểm rẽ ⇒ KHÔNG được trả null")
        assertEquals(0, r!!.turnMeters)
        assertEquals("Lý Thường Kiệt", r.road)
        assertEquals(195, r.nextTurnMeters, "node GIÀU hơn (biết 'Sau đó') phải thắng")

        // Thứ tự ngược lại cho kết quả y hệt (thứ tự node Flutter không bảo đảm).
        assertEquals(195, VietMapDescParser.parse(listOf(con, cha))!!.nextTurnMeters)

        // Lệch RIÊNG ở trường phụ ⇒ bỏ riêng trường phụ, quyết định vẫn giữ.
        val r2 = VietMapDescParser.parse(listOf(cha, "Sau đó (300m)\n0m Lý Thường Kiệt"))
        assertNotNull(r2)
        assertEquals(0, r2!!.turnMeters)
        assertEquals(VietMapDescParser.UNKNOWN, r2.nextTurnMeters, "hai 'Sau đó' lệch nhau ⇒ KHÔNG đoán")

        // Mâu thuẫn THẬT (khác cự ly / khác tên đường) vẫn phải im lặng — cổng không bị nới.
        assertNull(VietMapDescParser.parse(listOf(cha, "50m Lý Thường Kiệt")))
        assertNull(VietMapDescParser.parse(listOf(cha, "0m Lê Lai")))
    }
}
