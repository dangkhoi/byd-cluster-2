package com.byd.clusternav.navigation.screencapture

import com.byd.clusternav.navigation.ArrayPixelFrame
import com.byd.clusternav.navigation.ManeuverSignature
import com.byd.clusternav.navigation.PixelFrame
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá [NavGlyphLocator] + đường chấm [ManeuverSignature.classifyWazeInk] trên **8 ẢNH CHỤP THẬT**
 * (emulator, WazeMod đang dẫn, 2026-08-21/22).
 *
 * VÌ SAO CẦN CẢ MA TRẬN NÀY (yêu cầu owner 08-22): khi cast lên cụm, người dùng chỉnh được **dpi**,
 * **kích thước**, **vị trí** cửa sổ và cast **một hoặc hai** app (`CastShell`: `wm size` / `wm density` /
 * `am task resize` / chia đôi). Thêm nữa Waze tự đổi bố cục banner theo độ dài tên đường. Mọi rect crop cố
 * định đều chết ở ít nhất một trong các ca đó — nên locator phải chịu được cả ma trận.
 *
 * Fixture là **dải TRÊN** của ảnh chụp (pixel nguyên văn, chỉ bỏ phần dưới là bản đồ) để repo nhẹ; toạ độ
 * giữ nguyên nên bbox dò được so khớp thẳng với giá trị đo tay.
 */
class NavGlyphLocatorTest {

    private data class Case(
        val file: String, val dpi: Int, val screenW: Int, val screenH: Int, val expect: String,
    )

    private val cases = listOf(
        // ── CÙNG một maneuver (rẽ TRÁI, banner 1 dòng) qua 4 dpi và 4 kích thước màn ──
        Case("w1280h480-d160.png", 160, 1280, 480, "maneuver_turn_normal_left"),
        Case("w1600h600-d200.png", 200, 1600, 600, "maneuver_turn_normal_left"),
        Case("w1920h720-d160.png", 160, 1920, 720, "maneuver_turn_normal_left"),
        Case("w1920h720-d240.png", 240, 1920, 720, "maneuver_turn_normal_left"),
        Case("w1920h720-d320.png", 320, 1920, 720, "maneuver_turn_normal_left"),
        Case("w1920h1080-d240.png", 240, 1920, 1080, "maneuver_turn_normal_left"),
        // ── maneuver KHÁC (rẽ PHẢI) + bố cục banner 2 DÒNG ──
        Case("w1920h720-d240-2line.png", 240, 1920, 720, "maneuver_turn_normal_right"),
        Case("w1920h1080-d240-2line.png", 240, 1920, 1080, "maneuver_turn_normal_right"),
    )

    private fun frame(file: String): PixelFrame {
        val url = javaClass.getResource("/diagnostics/glyph/$file")
        assertNotNull(url, "thiếu fixture $file")
        val img = ImageIO.read(url)
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return ArrayPixelFrame(img.width, img.height, px)
    }

    /**
     * Ô cửa sổ app cho fixture = trọn khung ảnh.
     *
     * Fixture là **dải TRÊN** cắt từ ảnh chụp của một app đang chiếm trọn bề rộng màn, nên mép trái của khung
     * ĐÚNG là mép trái cửa sổ — đúng thứ [NavGlyphLocator.MAX_LEFT_ANCHOR] cần. Từ 08-23 vòng 3b [locate]
     * không nhận null nữa (xem KDoc của nó) nên phải nói ra ô cửa sổ ở mọi lời gọi.
     */
    private fun win(f: PixelFrame) = CropRect(0, 0, f.width, f.height)

    private fun crop(src: PixelFrame, r: CropRect): PixelFrame {
        val all = src.argb()!!
        val out = IntArray(r.width * r.height)
        for (y in 0 until r.height) {
            System.arraycopy(all, (r.top + y) * src.width + r.left, out, y * r.width, r.width)
        }
        return ArrayPixelFrame(r.width, r.height, out)
    }

    @Test
    fun `do duoc glyph o MOI dpi va MOI kich thuoc man`() {
        for (c in cases) {
            val f = frame(c.file)
            val ink = NavGlyphLocator.locate(f, win(f), c.dpi)
            assertNotNull(ink, "${c.file}: không dò ra glyph")
            // Kích thước nay chỉ còn SÀN (trần MAX_DP gỡ 08-23 vòng 3), và sàn có HAI vế — dp và tỉ lệ với
            // chiều cao cửa sổ (thêm 08-23 vòng 3b) — nên phải qua cả hai.
            val hDp = ink!!.height * 160f / c.dpi
            assertTrue(
                hDp >= NavGlyphLocator.MIN_DP,
                "${c.file}: chiều cao glyph ${"%.1f".format(hDp)}dp dưới sàn ${NavGlyphLocator.MIN_DP}dp",
            )
            assertTrue(
                ink.height.toFloat() / f.height >= NavGlyphLocator.MIN_H_WIN_FRAC,
                "${c.file}: cao ${ink.height}px trên cửa sổ ${f.height}px, dưới sàn ${NavGlyphLocator.MIN_H_WIN_FRAC}",
            )
        }
    }

    /**
     * KHOÁ **KHÔNG HỒI QUY WAZE** cho B3.47 vòng 3 — bbox từng khung phải y hệt từng pixel.
     *
     * VÌ SAO cần chốt cứng rect chứ không chỉ "dò ra được": vòng 3 gỡ trần `MAX_DP` (58 dp), nới `MAX_FILL`
     * 0.35 → 0.60 và thêm hai cổng tỉ số ([NavGlyphLocator.MAX_ASPECT], [NavGlyphLocator.MAX_LEFT_ANCHOR]).
     * Nới cổng có thể làm một đảo KHÁC (icon status bar, chữ cự ly) qua được rồi **thắng vì nằm bên trái**
     * mũi tên — lúc đó `locate` vẫn trả non-null, `classifyWazeInk` vẫn chạy, mọi test "dò ra được" vẫn
     * xanh, chỉ có hướng trên cụm là sai. Các con số dưới đây đo trên chính 8 fixture này TRƯỚC bản vá.
     *
     * ⚠ Đây là bbox **trên fixture dải**, không phải bbox on-car: với `w1920h720-d240-2line` cửa sổ thật cao
     * 720 cho (83,73,147,**148**) chứ không phải 144 — xem `ROI day du — dap dai Waze len ban do THAT`.
     */
    @Test
    fun `bbox Waze khong doi mot pixel nao sau khi thay cong (B3_47)`() {
        val expected = mapOf(
            "w1280h480-d160.png" to CropRect(18, 37, 48, 73),
            "w1600h600-d200.png" to CropRect(23, 47, 61, 91),
            "w1920h720-d160.png" to CropRect(48, 37, 78, 73),
            "w1920h720-d240.png" to CropRect(72, 56, 118, 109),
            "w1920h720-d320.png" to CropRect(36, 75, 98, 139),
            "w1920h1080-d240.png" to CropRect(72, 56, 118, 109),
            "w1920h720-d240-2line.png" to CropRect(83, 73, 147, 144),
            "w1920h1080-d240-2line.png" to CropRect(83, 73, 147, 139),
        )
        for (c in cases) {
            assertEquals(
                expected.getValue(c.file), frame(c.file).let { NavGlyphLocator.locate(it, win(it), c.dpi) },
                "${c.file}: bbox Waze đã đổi — cổng mới vừa đổi đảo được chọn",
            )
        }
    }

    @Test
    fun `classify dung huong o MOI cau hinh (dpi x size x bo cuc banner)`() {
        for (c in cases) {
            val f = frame(c.file)
            val ink = NavGlyphLocator.locate(f, win(f), c.dpi)!!
            val m = ManeuverSignature.classifyWazeInk(crop(f, ink))
            assertEquals(c.expect, m.name, "${c.file}: sai maneuver")
            assertNotNull(m.amap, "${c.file}: phải ra mã AMAP để đẩy lên cụm/HUD")
        }
    }

    /**
     * Rẽ trái phải ra **AMAP 2**, không phải 4. Đây KHÔNG phải chi tiết vụn: `nameToAmap` xét
     * `contains("ramp") && contains("left")` TRƯỚC `normal_left`, nên nếu khớp nhầm sang
     * `maneuver_off_ramp_normal_left` thì mã ra 4 = *chếch trái* — cụm vẽ sai glyph. Đúng lỗi này đã xảy ra
     * khi thử khớp bbox-mực với registry "khung vẽ" của OpenBYD (4/9 khung, đo 08-22).
     */
    @Test
    fun `re trai ra AMAP 2 chu khong phai 4 (chech trai)`() {
        val c = cases.first { it.expect == "maneuver_turn_normal_left" }
        val f = frame(c.file)
        val ink = NavGlyphLocator.locate(f, win(f), c.dpi)!!
        assertEquals(2, ManeuverSignature.classifyWazeInk(crop(f, ink)).amap)
    }

    /** Rẽ phải → AMAP 3. */
    @Test
    fun `re phai ra AMAP 3`() {
        val c = cases.first { it.expect == "maneuver_turn_normal_right" }
        val f = frame(c.file)
        val ink = NavGlyphLocator.locate(f, win(f), c.dpi)!!
        assertEquals(3, ManeuverSignature.classifyWazeInk(crop(f, ink)).amap)
    }

    /**
     * Bất biến định lượng của quy ước bbox-mực: CÙNG maneuver ở dpi/size khác nhau phải cho chữ ký GẦN nhau,
     * và maneuver khác nhau phải cho chữ ký XA nhau. Đây là thứ khiến registry chỉ cần vài template mà vẫn
     * phủ mọi cấu hình cast — nếu ai đổi cách crop/ký làm hai biên chồng lên nhau, test này gãy trước.
     */
    @Test
    fun `chu ky bat bien theo dpi_size — noi lop GAN, lien lop XA`() {
        fun sigOf(c: Case): String {
            val f = frame(c.file)
            val ink = NavGlyphLocator.locate(f, win(f), c.dpi)!!
            return ManeuverSignature.signatureBits(crop(f, ink))!!
        }
        val left = cases.filter { it.expect.endsWith("left") }.map(::sigOf)
        val right = cases.filter { it.expect.endsWith("right") }.map(::sigOf)
        fun ham(a: String, b: String) = a.indices.count { a[it] != b[it] }

        var intraMax = 0
        for (i in left.indices) for (j in i + 1 until left.size) intraMax = maxOf(intraMax, ham(left[i], left[j]))
        var interMin = Int.MAX_VALUE
        for (a in left) for (b in right) interMin = minOf(interMin, ham(a, b))

        assertTrue(intraMax <= 18, "nội-lớp phải nằm trong ngưỡng khớp 18 bit, đo được $intraMax")
        assertTrue(interMin >= 40, "liên-lớp phải cách xa, đo được $interMin")
        assertTrue(interMin > intraMax * 2, "biên nội/liên lớp quá sát: intra=$intraMax inter=$interMin")
    }

    /**
     * Cửa sổ app KHÔNG chiếm cả display (ca cast 2 app chia đôi / người dùng dời vị trí): locator phải tôn
     * trọng `windowRect`. Cắt nửa PHẢI làm cửa sổ → không còn glyph trong đó → phải trả null, KHÔNG được
     * quét lấn sang nửa kia rồi báo bừa.
     */
    @Test
    fun `ton trong windowRect — nua KHONG chua glyph thi tra null`() {
        val c = cases.first { it.file == "w1920h720-d240.png" }
        val f = frame(c.file)
        val rightHalf = CropRect(f.width / 2, 0, f.width, f.height)
        assertNull(NavGlyphLocator.locate(f, rightHalf, c.dpi), "nửa phải không có glyph mà vẫn báo có")
        val leftHalf = CropRect(0, 0, f.width / 2, f.height)
        assertNotNull(NavGlyphLocator.locate(f, leftHalf, c.dpi), "nửa trái có glyph mà không dò ra")
    }

    /** Khung trống (toàn đen) → null, không bịa glyph. */
    @Test
    fun `khung trong thi tra null`() {
        val blank = ArrayPixelFrame(400, 200, IntArray(400 * 200) { 0xFF000000.toInt() })
        assertNull(NavGlyphLocator.locate(blank, win(blank), 240))
    }

    // ── DƯƠNG TÍNH GIẢ (khung chụp THẬT, thu tự động 2026-08-22; cổng đo lại 08-23 vòng 3b) ───────────────
    // Năm cổng sinh ra từ số đo, mỗi cổng gánh một loại rác khác nhau:
    //   ĐỘ TỐI TỪNG PHÍA          → đốm sáng trên bản đồ ngày
    //   SÀN theo dp               → icon status bar (9×15 px @dpi240)
    //   SÀN theo %chiều-cao-cửa-sổ → CŨNG icon status bar, nhưng đứng vững cả khi dpi khai SAI (vòng 3b)
    //   TỈ LỆ LẤP + KHUNG         → chữ đặc, vệt dài
    //   NEO TRÁI                  → dải LÀN (neo 3.16) và mọi icon giữa bản đồ (POI xe buýt neo 15.46)
    // Nới bất kỳ cổng nào = mở đường cho mũi tên GIẢ lên cụm/HUD, nên test canh trực tiếp.

    /**
     * Khung "Proceed to highlighted route" — Waze KHÔNG hiện mũi tên maneuver nào. Bản nháp của locator vồ
     * phải cụm icon status bar nằm ngay TRÊN banner đen: trái/phải nó sáng trưng (trung vị 139–144) nhưng
     * khối banner đen phía dưới quá lớn nên kéo trung vị CHUNG xuống dưới ngưỡng ⇒ lọt. Vì vậy phải xét
     * độ tối TỪNG PHÍA, không lấy trung vị chung.
     */
    @Test
    fun `khong co mui ten thi KHONG duoc bat nham icon status bar`() {
        val f = frame("neg-statusbar-no-arrow-d240.png")
        assertNull(NavGlyphLocator.locate(f, win(f), 240), "bắt nhầm icon status bar thành mũi tên")
    }

    /**
     * Dải LÀN (4 mũi tên làn xếp hàng, cái được chọn tô trắng) — là tín hiệu làn, KHÔNG phải maneuver kế
     * tiếp. Đẩy nó vào kênh mũi tên là báo sai hướng rẽ.
     *
     * ⚠ CƠ CHẾ CHẶN ĐÃ ĐỔI 08-23 vòng 3: trước là cổng lấp (làn 0.504 > trần 0.35), nay trần lấp là 0.60 nên
     * cổng đó KHÔNG còn chặn nó. Thứ chặn bây giờ là [NavGlyphLocator.MAX_LEFT_ANCHOR]: đảo làn duy nhất qua
     * được vành-tối nằm ở (136,68) 43×31 ⇒ neo trái **3.16** > trần 2.0. [ĐO] cùng ngày — đây chính là lý do
     * KHÔNG được nới trần lấp nếu chưa có cổng neo trái.
     */
    @Test
    fun `dai LAN khong duoc nham thanh mui ten maneuver`() {
        val f = frame("neg-lanestrip-d240.png")
        assertNull(NavGlyphLocator.locate(f, win(f), 240), "bắt nhầm mũi tên làn thành maneuver")
    }

    /**
     * Mọi số đo của mũi tên Waze THẬT phải cách mép cổng ≥ 10 %.
     *
     * ⚠ 08-23 vòng 3b — bản trước chỉ assert `fill in (MIN_FILL, MAX_FILL)` và vì trần lấp vừa nới 0.35→0.60
     * nên vế trên gần như rỗng nghĩa (đo được 0.207–0.247 trong [0.10, 0.60]) dù TÊN test hứa "biên an toàn".
     * Nay canh biên thật, và canh CẢ **neo trái** — ca căng nhất của toàn corpus là Waze `w1920h720-d240`
     * (**1.358** / trần 2.0), không phải VietMap (max 1.119), nên `VietMapGlyphGateTest` một mình KHÔNG phủ.
     */
    @Test
    fun `so do mui ten Waze that cach mep MOI cong it nhat 10 phan tram`() {
        val margin = 0.10f
        for (c in cases) {
            val f = frame(c.file)
            val ink = NavGlyphLocator.locate(f, win(f), c.dpi)!!
            val px = f.argb()!!
            var bright = 0
            for (y in ink.top until ink.bottom) for (x in ink.left until ink.right) {
                val v = px[y * f.width + x]
                if ((((v ushr 16) and 0xFF) + ((v ushr 8) and 0xFF) + (v and 0xFF)) / 3 > NavGlyphLocator.BRIGHT) bright++
            }
            val fill = bright.toFloat() / (ink.width * ink.height)
            assertTrue(
                fill >= NavGlyphLocator.MIN_FILL * (1 + margin) && fill <= NavGlyphLocator.MAX_FILL * (1 - margin),
                "${c.file}: lấp $fill sát mép cổng [${NavGlyphLocator.MIN_FILL}, ${NavGlyphLocator.MAX_FILL}]",
            )
            val aspect = ink.width.toFloat() / ink.height
            assertTrue(
                aspect >= NavGlyphLocator.MIN_ASPECT * (1 + margin) && aspect <= NavGlyphLocator.MAX_ASPECT * (1 - margin),
                "${c.file}: tỉ lệ khung $aspect sát mép [${NavGlyphLocator.MIN_ASPECT}, ${NavGlyphLocator.MAX_ASPECT}]",
            )
            val anchor = ink.left.toFloat() / maxOf(ink.width, ink.height)
            assertTrue(
                anchor <= NavGlyphLocator.MAX_LEFT_ANCHOR * (1 - margin),
                "${c.file}: neo trái $anchor sát trần ${NavGlyphLocator.MAX_LEFT_ANCHOR}",
            )
            // Sàn: cả hai vế đều phải còn dư địa, vì sàn thật = max của chúng.
            val hDp = ink.height * 160f / c.dpi
            assertTrue(hDp >= NavGlyphLocator.MIN_DP * (1 + margin), "${c.file}: ${"%.1f".format(hDp)}dp sát sàn dp")
            val hFrac = ink.height.toFloat() / f.height
            assertTrue(
                hFrac >= NavGlyphLocator.MIN_H_WIN_FRAC * (1 + margin),
                "${c.file}: cao $hFrac lần chiều cao cửa sổ, sát sàn ${NavGlyphLocator.MIN_H_WIN_FRAC}",
            )
        }
    }

    /**
     * Bản đồ **TỐI** (VietMap chế độ đêm) đang KHÔNG dẫn: đầy icon sáng (ghim đỗ xe, đèn giao thông, biển
     * tốc độ, đồng hồ) trên nền tối. Đây là ca khó nhất cho ràng buộc "đảo sáng có vành tối" — nền tối làm
     * vành tối trở nên tầm thường, nên chỉ còn sàn nhìn-được + hai cổng tỉ số + neo trái gánh. Phải trả null.
     *
     * ⚠ ĐÍNH CHÍNH 08-23 vòng 3b — bản trước ghi *"12 đảo … neo trái loại 7 (2.07 … 93.0), sàn loại 3, tỉ lệ
     * khung loại 2"*. ĐO LẠI bằng chính thuật toán của locator: **11** đảo qua vành-tối, và **cả 11 đều rớt ở
     * SÀN trước tiên** (đảo cao nhất chỉ 26 px < sàn 30 px @dpi240) — neo trái làm **0** việc trên khung này,
     * neo lớn nhất là **53.67**, không có 93.0. Tức khung âm này khoá SÀN, không khoá neo trái; muốn khoá neo
     * trái thì xem `dai LAN khong duoc nham thanh mui ten maneuver` (3.16) và `VietMapGlyphGateTest`.
     */
    @Test
    fun `ban do TOI khong dan — khong bat nham icon nao`() {
        val f = frame("neg-vietmap-darkmap-idle-d240.png")
        assertNull(NavGlyphLocator.locate(f, win(f), 240), "bắt nhầm icon trên bản đồ tối")
    }

    /**
     * KHOÁ **ROI ĐẦY ĐỦ** — 8 fixture Waze là **dải TRÊN** (cao 240–320 px) chứ không phải khung đầy đủ, mà
     * `roiH = min(0.45·win.height, 220dp·scale)` lấy chiều cao của chính ảnh ⇒ mọi test khác chỉ chạy trên
     * **~44 %** diện tích quét thật (vd `w1920h1080-d240`: roiH 144 khi test vs **330** trên máy).
     * Phần bị cắt đúng là **vùng bản đồ** — nơi cổng vừa nới phơi ra nhiều nhất và cũng là nơi đảo mồi POI
     * của B3.47 sinh sống.
     *
     * Test này đắp dải Waze lên trên một nền **bản đồ THẬT** (các hàng phía dưới của khung nền VietMap, giữ
     * nguyên pixel — có đủ POI xe buýt, biển tốc độ, ghim đỗ xe) cho tới đúng chiều cao màn của từng ca, rồi
     * đòi bbox không đổi. Nếu ROI rộng ra làm một icon bản đồ thắng, đỏ ở đây.
     *
     * [ĐO] 08-23 vòng 3b — **không** có icon bản đồ nào thắng ở bất kỳ ca nào, và 7/8 bbox giống hệt. Ca thứ
     * 8 lệch, và lệch theo hướng ngược với lo ngại: `w1920h720-d240-2line` cho **(83,73,147,148) 64×75** thay
     * vì (83,73,147,144) 64×71 — tức con số **144 chốt trong `bbox Waze khong doi mot pixel nao` là ARTEFACT
     * CỦA FIXTURE**, không phải bbox trên máy: dải chỉ cao 320 px ⇒ `roiH = min(0.45·320, 220dp·1.5) = 144`
     * cắt mất 4 hàng đáy mũi tên; cửa sổ THẬT cao 720 ⇒ `roiH = 324` ⇒ lấy đủ. Hướng chấm ra KHÔNG đổi
     * (`maneuver_turn_normal_right`, amap 3) ở cả hai bbox, nên đây là chênh lệch vô hại đã đo, không phải
     * hồi quy — nhưng phải ghi ra để không ai coi 8 con số kia là "bbox on-car".
     */
    @Test
    fun `ROI day du — dap dai Waze len ban do THAT, bbox khong doi (B3_47b)`() {
        val mapBg = run {
            val url = javaClass.getResource("/diagnostics/vietmap-glyph/_base-vietmap-1920x1080-d240.png")
            assertNotNull(url, "thiếu khung nền bản đồ")
            val img = ImageIO.read(url)
            val px = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            Triple(img.width, img.height, px)
        }
        val expected = mapOf(
            "w1280h480-d160.png" to CropRect(18, 37, 48, 73),
            "w1600h600-d200.png" to CropRect(23, 47, 61, 91),
            "w1920h720-d160.png" to CropRect(48, 37, 78, 73),
            "w1920h720-d240.png" to CropRect(72, 56, 118, 109),
            "w1920h720-d320.png" to CropRect(36, 75, 98, 139),
            "w1920h1080-d240.png" to CropRect(72, 56, 118, 109),
            // ⚠ 148 chứ không phải 144 — xem KDoc: 144 là chỗ dải fixture (cao 320) bị `roiH` cắt.
            "w1920h720-d240-2line.png" to CropRect(83, 73, 147, 148),
            "w1920h1080-d240-2line.png" to CropRect(83, 73, 147, 139),
        )
        val (bw, bh, bpx) = mapBg
        for (c in cases) {
            val strip = frame(c.file)
            val sp = strip.argb()!!
            val out = IntArray(strip.width * c.screenH)
            for (y in 0 until c.screenH) for (x in 0 until strip.width) {
                out[y * strip.width + x] =
                    if (y < strip.height) sp[y * strip.width + x]
                    else bpx[(y % bh) * bw + (x % bw)]                  // hàng bản đồ THẬT, lặp cho đủ khung
            }
            val tall = ArrayPixelFrame(strip.width, c.screenH, out)
            val ink = NavGlyphLocator.locate(tall, win(tall), c.dpi)
            assertEquals(
                expected.getValue(c.file), ink,
                "${c.file}: ROI đầy đủ (cao ${c.screenH}) làm đổi đảo được chọn",
            )
            // Và hướng chấm ra phải y hệt ca dải — bbox chênh vài pixel không được đổi kết luận.
            assertEquals(
                c.expect, ManeuverSignature.classifyWazeInk(crop(tall, ink!!)).name,
                "${c.file}: ROI đầy đủ làm đổi HƯỚNG",
            )
        }
    }
}
