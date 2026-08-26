package com.byd.clusternav.navigation.screencapture

import com.byd.clusternav.navigation.ArrayPixelFrame
import com.byd.clusternav.navigation.ManeuverSignature
import com.byd.clusternav.navigation.PixelFrame
import com.byd.clusternav.navigation.WazeArrowRegistry
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Đo **phủ sóng THẬT** của [NavGlyphLocator] trên toàn bộ 87 maneuver VietMap, và khoá các đường
 * **dương-tính-giả** mà B3.47 vòng 3 / 3b phát hiện.
 *
 * ── FIXTURE: KHUNG GHÉP, KHÔNG PHẢI KHUNG MÔ PHỎNG ──────────────────────────────────────────────────────
 * `core/src/test/resources/diagnostics/vietmap-glyph/` gồm:
 *   · `_base-vietmap-1920x1080-d240.png` — khung chụp THẬT trên emulator (VietMap Live 3.3.4 đang dẫn,
 *     dpi 240), đã **xoá riêng mũi tên** khỏi banner. Mọi thứ khác giữ nguyên pixel: chữ cự ly, tên đường,
 *     dải làn, biển tốc độ, status bar, và **bản đồ phía dưới** (chính chỗ có icon POI ở §"đảo mồi").
 *   · 93 PNG glyph 110×110 render từ **chính asset** `flutter_assets/lib/assets/maps/directions_white/` (SVG)
 *     của APK, thu về đúng cỡ hiển thị trên màn (công thức: `docs/diagnostics/vietmap-glyph-gate-measurement-2026-08-23.md` §1).
 *   · `index.json` — `origin` = gốc canvas trên khung, `glyphs{}` = số đo từng glyph.
 * Dán glyph lên base tại `origin` ⇒ khung 1920×1080 y như máy vẽ ra.
 *
 * **Độ trung thực đã kiểm 3 lần độc lập [ĐO]** (§2 của tài liệu trên): `turn_straight` ghép lại cho ink
 * (63,76) 51×96 lấp 0.352 = đúng khung chụp gốc; `turn_right` ghép cho rect **(40,85)-(135,172)** = đúng log
 * on-car v1.11 `rect=(40,85,135,172)`; lấp khung dựng vs ảnh thật lệch **0.002**.
 *
 * ── VÌ SAO TEST NÀY THAY `WazeArrowRegistryTest.phu song that qua cong NavGlyphLocator` (đã gỡ) ───────────
 * Test cũ ước lượng phủ sóng bằng **proxy lưới 15×15** của chuỗi chữ ký. Lưới đó đã CHUẨN HOÁ kích thước
 * nên về nguyên tắc không thể nhìn thấy cổng chiều cao — nó quy sai thủ phạm cho `MAX_FILL` và ra con số
 * "17/34 template". Ở đây ta chạy **chính `NavGlyphLocator`** trên khung 1920×1080 nên số đo là thật.
 *
 * ── TIÊU CHÍ "DÒ ĐÚNG" (đổi 08-23 vòng 3b) ───────────────────────────────────────────────────────────────
 * Bản trước đếm hit bằng `rect.left < 250`. Tiêu chí đó **không phân biệt được mũi tên với bất cứ thứ gì
 * khác nằm bên trái** — [ĐO] khi dpi khai < 128 thì locator trả icon status bar `(14,11,23,26)` cho **87/87**
 * maneuver mà test cũ vẫn báo "hit 87". Nay hit = rect nằm **trọn trong ô canvas glyph đã dán**
 * ([glyphCanvas], suy từ `origin` + cỡ canvas trong `index.json`), tức đúng vùng pixel mà ta vừa vẽ mũi tên
 * vào — không có cách nào thoả bằng một đảo khác.
 */
class VietMapGlyphGateTest {

    /** 6 icon KHÔNG phải chỉ dẫn rẽ (nút đóng, cờ đích, biển đường, mũi tên lên-xuống danh sách). */
    private val nonManeuver = setOf("close", "flag", "updown", "road_icon_left", "road_icon_right", "invalid")

    /**
     * Đảo mồi [ĐO] 08-23 vòng 3: icon POI **điểm dừng xe buýt trên BẢN ĐỒ** (chữ trắng trong ô bo góc, nền
     * tối bao quanh) — nó qua sạch cả bốn ràng buộc của bản locator CŨ (đảo sáng ✓ vành tối 4 phía ✓
     * 39×39 px ∈ [30,87] ✓ lấp 0.106 ∈ [0.10,0.35] ✓) và nằm cách mũi tên **560 px** về bên phải.
     */
    private val decoy = CropRect(603, 78, 642, 117)

    @AfterEach fun restoreRegistry() = WazeArrowRegistry.clear()

    private fun png(path: String): BufferedImage {
        val url = javaClass.getResource(path)
        assertNotNull(url, "thiếu fixture $path")
        return ImageIO.read(url)!!
    }

    private fun pixels(img: BufferedImage): IntArray =
        IntArray(img.width * img.height).also { img.getRGB(0, 0, img.width, img.height, it, 0, img.width) }

    /** Tên maneuver + gốc dán, đọc từ `index.json` (không thêm phụ thuộc JSON — file do ta sinh, dạng cố định). */
    private val index: String by lazy {
        javaClass.getResourceAsStream("/diagnostics/vietmap-glyph/index.json")!!.bufferedReader().readText()
    }

    private val origin: Pair<Int, Int> by lazy {
        val m = Regex(""""origin"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""").find(index)
        assertNotNull(m, "index.json thiếu \"origin\"")
        m!!.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private val canvasSize: Int by lazy {
        val m = Regex(""""canvas"\s*:\s*(\d+)""").find(index)
        assertNotNull(m, "index.json thiếu \"canvas\"")
        m!!.groupValues[1].toInt()
    }

    /** Ô pixel mà glyph được dán vào — mọi rect "dò đúng" phải nằm TRỌN trong đây. */
    private val glyphCanvas: CropRect by lazy {
        val (ox, oy) = origin
        CropRect(ox, oy, ox + canvasSize, oy + canvasSize)
    }

    private fun inCanvas(r: CropRect?): Boolean = r != null &&
        r.left >= glyphCanvas.left && r.top >= glyphCanvas.top &&
        r.right <= glyphCanvas.right && r.bottom <= glyphCanvas.bottom

    private val maneuverNames: List<String> by lazy {
        Regex(""""([a-z_]+)"\s*:\s*\{""").findAll(index)
            .map { it.groupValues[1] }
            .filter { it != "glyphs" && it !in nonManeuver }
            .toList()
            .sorted()
    }

    private val base: BufferedImage by lazy { png("/diagnostics/vietmap-glyph/_base-vietmap-1920x1080-d240.png") }

    /** Ô cửa sổ app = trọn khung (khung nền là ảnh chụp app chiếm cả màn). */
    private val window: CropRect by lazy { CropRect(0, 0, base.width, base.height) }

    /** Dán glyph [name] lên khung nền tại `origin` ⇒ khung 1920×1080 để chạy locator. */
    private fun compose(name: String): PixelFrame {
        val px = pixels(base)
        val g = png("/diagnostics/vietmap-glyph/$name.png")
        val gp = pixels(g)
        val (ox, oy) = origin
        for (y in 0 until g.height) {
            val ty = oy + y
            if (ty !in 0 until base.height) continue
            for (x in 0 until g.width) {
                val tx = ox + x
                if (tx in 0 until base.width) px[ty * base.width + tx] = gp[y * g.width + x]
            }
        }
        return ArrayPixelFrame(base.width, base.height, px)
    }

    private fun locate(name: String): CropRect? = NavGlyphLocator.locate(compose(name), window, DPI)

    private fun crop(src: PixelFrame, r: CropRect): PixelFrame {
        val all = src.argb()!!
        val out = IntArray(r.width * r.height)
        for (y in 0 until r.height) System.arraycopy(all, (r.top + y) * src.width + r.left, out, y * r.width, r.width)
        return ArrayPixelFrame(r.width, r.height, out)
    }

    // ── (1) BẢNG PHỦ SÓNG — con số này là hợp đồng, đổi cổng là đổi bảng ────────────────────────────────

    /**
     * KHOÁ bảng dò TRƯỚC/SAU của B3.47 vòng 3.
     *
     * | | trước (MAX_DP 58 dp · MAX_FILL 0.35) | sau (tỉ số + neo trái) |
     * |---|---:|---:|
     * | dò ĐÚNG mũi tên | 27 | **86** |
     * | trả **ĐẢO MỒI** (POI bản đồ) | 60 | **0** |
     * | trả null | 0 | 1 |
     *
     * `fork_straight` là ca null DUY NHẤT. Nó **không phải hành vi thiết kế** mà là biên của [ringIsDark]:
     * [ĐO] 08-23 vòng 3b, `fork_straight` và `continue_straight` có bbox **giống nhau từng pixel**
     * (63,76)-(114,172) 51×96, lấp lệch **0.002** (0.3523 vs 0.3503), một cái rớt vành-tối một cái qua.
     * Câu trả lời "null" vẫn là câu trả lời AN TOÀN (im lặng), và trước bản vá chính nó trả về đảo mồi — nên
     * danh sách này được chốt cứng để ai chỉnh `DARK`/`RING_DP` phải nhìn thấy hệ quả, KHÔNG phải để khẳng
     * định rằng `fork_straight` xứng đáng bị bỏ.
     */
    @Test
    fun `phu song that — 86 do dung, 0 dao moi, 1 null tren 87 maneuver VietMap`() {
        var hit = 0
        val decoys = ArrayList<String>()
        val nulls = ArrayList<String>()
        for (n in maneuverNames) {
            val r = locate(n)
            when {
                r == null -> nulls += n
                inCanvas(r) -> hit++
                else -> decoys += "$n=$r"
            }
        }
        assertEquals(87, maneuverNames.size, "số maneuver trong fixture đã đổi")
        assertEquals(
            emptyList<String>(), decoys,
            "locator trả về đảo NGOÀI ô glyph $glyphCanvas — đây là crop SAI đi thẳng vào classifyWazeInk. " +
                "Không dò được thì phải trả null.",
        )
        assertEquals(listOf("fork_straight"), nulls, "tập maneuver trả null đã đổi")
        assertEquals(86, hit, "phủ sóng dò-đúng đã đổi (trước bản vá B3.47 vòng 3: 27/87)")
    }

    /**
     * KHOÁ [P1] — đảo mồi cụ thể không bao giờ được trả về, ở BẤT KỲ maneuver nào.
     *
     * Đây là hàng rào riêng, KHÔNG gộp vào test trên: nếu ai đó nới cổng làm mũi tên rớt trở lại, test trên
     * đỏ ở ô "dò đúng" (dễ bị chữa bằng cách hạ con số), còn test này đỏ ở đúng cái nguy hiểm — **một icon
     * bản đồ được đưa cho matcher như thể là chỉ dẫn rẽ**.
     */
    @Test
    fun `khong maneuver nao tra ve icon POI tren ban do`() {
        val caught = maneuverNames.filter { locate(it) == decoy }
        assertEquals(
            emptyList<String>(), caught,
            "$caught trả về đảo mồi $decoy (icon POI xe buýt trên bản đồ, cách mũi tên 560 px). " +
                "Trước B3.47 vòng 3 có 60/87 ca như vậy.",
        )
    }

    /**
     * Khung nền KHÔNG có mũi tên (chưa dán glyph) phải trả **null**.
     *
     * Đây là bản rút gọn nhất của cả lỗi: cùng một khung, chỉ khác chỗ có/không có mũi tên. Bản locator cũ
     * trả về đảo mồi cho khung này — tức nó "thấy" chỉ dẫn rẽ ở nơi không có chỉ dẫn nào.
     */
    @Test
    fun `khung nen KHONG co mui ten thi im lang`() {
        val frame = ArrayPixelFrame(base.width, base.height, pixels(base))
        assertNull(
            NavGlyphLocator.locate(frame, window, DPI),
            "không có mũi tên trên banner mà locator vẫn trả bbox",
        )
    }

    // ── (2) CỔNG MỚI PHẢI LÀ TỈ SỐ — bất biến khi đổi thang ────────────────────────────────────────────

    /**
     * BẤT BIẾN TỈ LỆ (ràng buộc owner 08-23: *"vì nó có thể chỉnh DPI nên phải có giải pháp cho việc này,
     * không hardcode được đâu"*).
     *
     * Phóng khung ×2 **và** khai báo dpi ×2 (đúng thứ xảy ra khi người dùng `wm density` hoặc đổi cỡ cửa sổ
     * cast): mọi bbox dò được phải là đúng bbox cũ ×2. Cổng nào còn mang px/dp tuyệt đối SAI thang sẽ làm
     * một phần glyph rụng ở đây — đó chính là cách `MAX_DP` từng giết cả họ `straight`/`slight`/`roundabout`.
     * Vế "×2" cũng phủ [NavGlyphLocator.MIN_H_WIN_FRAC] (chiều cao cửa sổ ×2 ⇒ sàn ×2).
     */
    @Test
    fun `bat bien ti le — phong khung x2 va dpi x2 cho cung ket qua x2`() {
        val sample = listOf(
            "turn_right", "turn_straight", "turn_sharp_left", "uturn",
            "roundabout_slight_right", "arrive_left", "merge_left", "off_ramp_right",
        )
        for (n in sample) {
            val one = locate(n)
            assertNotNull(one, "$n: khung gốc không dò ra glyph")
            val big = scale2x(compose(n))
            val two = NavGlyphLocator.locate(big, CropRect(0, 0, big.width, big.height), DPI * 2)
            assertNotNull(two, "$n: khung ×2 không dò ra glyph — còn cổng nào đó không bất biến tỉ lệ")
            assertEquals(
                CropRect(one!!.left * 2, one.top * 2, one.right * 2, one.bottom * 2), two,
                "$n: bbox ở thang ×2 không bằng bbox gốc ×2",
            )
        }
    }

    /** Nhân đôi từng pixel theo cả hai chiều (nearest — giữ nguyên biên mực, không tạo mức xám mới). */
    private fun scale2x(src: PixelFrame): PixelFrame {
        val s = src.argb()!!
        val w = src.width * 2
        val h = src.height * 2
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = y / 2 * src.width
            for (x in 0 until w) out[y * w + x] = s[sy + x / 2]
        }
        return ArrayPixelFrame(w, h, out)
    }

    /**
     * Số đo của mọi glyph ĐƯỢC CHỌN phải nằm trong cổng tỉ số đã khai, **có biên hai đầu**.
     *
     * Test này canh chuyện "lọt với biên 0" — đúng cái bẫy của `MAX_DP` cũ (`turn_right` cao đúng 87 px =
     * trần, lệch một pixel là mất cả họ maneuver). Nếu một cổng bị siết tới sát số đo thật, đỏ ở đây trước.
     *
     * ⚠ Chỉ phủ bộ VietMap. Ca căng nhất của **neo trái** trong toàn corpus là **Waze** (1.358) — canh ở
     * `NavGlyphLocatorTest.so do mui ten Waze that cach mep MOI cong it nhat 10 phan tram`.
     */
    @Test
    fun `moi glyph chon duoc deu cach mep cong it nhat 10 phan tram`() {
        val margin = 0.10f
        for (n in maneuverNames) {
            val r = locate(n) ?: continue
            val px = compose(n).argb()!!
            var ink = 0
            for (y in r.top until r.bottom) for (x in r.left until r.right) {
                val c = px[y * base.width + x]
                if ((((c ushr 16) and 0xFF) + ((c ushr 8) and 0xFF) + (c and 0xFF)) / 3 > NavGlyphLocator.BRIGHT) ink++
            }
            val fill = ink.toFloat() / (r.width * r.height)
            val aspect = r.width.toFloat() / r.height
            val anchor = (r.left - window.left).toFloat() / maxOf(r.width, r.height)
            assertTrue(
                fill >= NavGlyphLocator.MIN_FILL * (1 + margin) && fill <= NavGlyphLocator.MAX_FILL * (1 - margin),
                "$n: lấp $fill sát mép cổng [${NavGlyphLocator.MIN_FILL}, ${NavGlyphLocator.MAX_FILL}]",
            )
            assertTrue(
                aspect >= NavGlyphLocator.MIN_ASPECT * (1 + margin) &&
                    aspect <= NavGlyphLocator.MAX_ASPECT * (1 - margin),
                "$n: tỉ lệ khung $aspect sát mép [${NavGlyphLocator.MIN_ASPECT}, ${NavGlyphLocator.MAX_ASPECT}]",
            )
            assertTrue(
                anchor <= NavGlyphLocator.MAX_LEFT_ANCHOR * (1 - margin),
                "$n: neo trái $anchor sát trần ${NavGlyphLocator.MAX_LEFT_ANCHOR}",
            )
            assertTrue(
                r.height.toFloat() / window.height >= NavGlyphLocator.MIN_H_WIN_FRAC * (1 + margin),
                "$n: cao ${r.height}px trên cửa sổ ${window.height}px, sát sàn ${NavGlyphLocator.MIN_H_WIN_FRAC}",
            )
        }
    }

    // ── (3) HAI ĐƯỜNG DƯƠNG-TÍNH-GIẢ CÒN LẠI (phát hiện ở phản biện vòng 3b) ──────────────────────────

    /**
     * KHOÁ [P1] — **dpi KHAI SAI không được biến icon status bar thành chỉ dẫn rẽ**.
     *
     * `dpi` đến từ regex `(\d{2,4})dpi` trên output `am stack list` ([CaptureLocationResolver]) — một chuỗi
     * shell, và dự án chạy 2 display nên nó có thể là dpi của display KHÁC với display đang chụp. Khi sàn
     * chỉ có vế dp ([NavGlyphLocator.MIN_DP] × dpi/160), [ĐO] 08-23 vòng 3b trên khung vẽ ở dpi 240:
     * ```
     * dpi khai <= 124 -> (14,11,23,26) 9x15 = ICON STATUS BAR, cho CẢ 87/87 maneuver
     * dpi khai >= 128 -> mũi tên
     * ```
     * Tức 100 % dương-tính-giả, và bộ test cũ (`rect.left < 250`) báo "hit 87/87" — xanh trong khi hỏng hoàn
     * toàn. Vế sàn thứ hai [NavGlyphLocator.MIN_H_WIN_FRAC] đóng đường này vì nó không đi qua `dpi`.
     *
     * Yêu cầu: ở MỌI dpi khai, kết quả chỉ được là **mũi tên** hoặc **null**, không bao giờ là thứ khác.
     */
    @Test
    fun `dpi khai SAI chi duoc lam mat mui ten, KHONG duoc doi sang dao khac (B3_47b)`() {
        val sample = listOf("turn_right", "turn_straight", "uturn", "merge_left", "rotary_left", "fork_straight")
        val bad = ArrayList<String>()
        for (n in sample) {
            val f = compose(n)
            for (dpi in listOf(0, 60, 80, 100, 120, 124, 128, 140, 160, 200, 240, 320, 400, 480)) {
                val r = NavGlyphLocator.locate(f, window, dpi)
                if (r != null && !inCanvas(r)) bad += "$n@dpi$dpi=$r"
            }
        }
        assertEquals(
            emptyList<String>(), bad,
            "dpi khai sai làm locator trả đảo NGOÀI ô glyph $glyphCanvas (trước vòng 3b: icon status bar " +
                "(14,11,23,26) cho mọi maneuver khi dpi khai ≤ 124)",
        )
        // Và ở dpi ĐÚNG thì vẫn phải dò được — sàn mới không được siết quá tay.
        assertTrue(inCanvas(NavGlyphLocator.locate(compose("turn_right"), window, DPI)), "dpi đúng mà mất mũi tên")
    }

    /**
     * KHOÁ [P1] — **chia đôi màn: không được trả mũi tên của APP KIA**.
     *
     * Dựng khung 1920×1080 từ hai fixture THẬT: nửa TRÁI = Waze `w1920h720-d240.png` (rẽ **TRÁI**), nửa PHẢI
     * = khung VietMap `turn_right` (rẽ **PHẢI**). Đây là ca `CastShell` chia đôi cụm cho 2 app.
     *
     * [ĐO] 08-23 vòng 3b — trước bản vá, `ScreenCaptureNavSource` truyền thẳng `loc.windowRect` và locator
     * hiểu `null` = "cả khung":
     * ```
     * window = nửa phải  -> (1000,85,1095,172) -> maneuver_turn_normal_right amap=3  ✓ đúng app
     * window = CẢ KHUNG  -> (  72,56, 118,109) -> maneuver_turn_normal_left  amap=2  ✗ mũi tên của Waze
     * ```
     * Hamming 0 ⇒ khớp chắc chắn ⇒ `publishArrow(pkg=VietMap, amap=2)`: cụm vẽ RẼ TRÁI trong khi VietMap
     * đang bảo RẼ PHẢI. Nay [NavGlyphLocator.locate] không nhận null (kiểu Kotlin chặn) và
     * `ScreenCaptureNavSource.handleArrowByGlyph` `return false` khi `windowRect == null`.
     *
     * Test giữ CẢ vế "cả khung" để khoá lại **cơ chế** của lỗi: nó phải vẫn trả mũi tên Waze — đó chính là lý
     * do không được phép đoán ô cửa sổ.
     */
    @Test
    fun `chia doi man — moi nua tra dung mui ten cua app trong nua do (B3_47b)`() {
        val w = 1920
        val h = 1080
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        val waze = png("/diagnostics/glyph/w1920h720-d240.png")
        val wp = pixels(waze)
        for (y in 0 until minOf(waze.height, h)) for (x in 0 until w / 2) px[y * w + x] = wp[y * waze.width + x]
        val vm = compose("turn_right").argb()!!
        for (y in 0 until h) for (x in 0 until w / 2) px[y * w + w / 2 + x] = vm[y * base.width + x]
        val split = ArrayPixelFrame(w, h, px)

        val right = NavGlyphLocator.locate(split, CropRect(w / 2, 0, w, h), DPI)
        assertEquals(CropRect(1000, 85, 1095, 172), right, "nửa phải (VietMap) trả sai bbox")
        assertEquals(3, ManeuverSignature.classifyWazeInk(crop(split, right!!)).amap, "nửa phải phải ra RẼ PHẢI")

        val left = NavGlyphLocator.locate(split, CropRect(0, 0, w / 2, h), DPI)
        assertEquals(CropRect(72, 56, 118, 109), left, "nửa trái (Waze) trả sai bbox")
        assertEquals(2, ManeuverSignature.classifyWazeInk(crop(split, left!!)).amap, "nửa trái phải ra RẼ TRÁI")

        // Ô cửa sổ SAI (= nghĩa cũ của `window == null`) ⇒ trả mũi tên của app KIA. Khoá lại cơ chế lỗi.
        val whole = NavGlyphLocator.locate(split, CropRect(0, 0, w, h), DPI)
        assertEquals(
            left, whole,
            "đoán ô cửa sổ = cả khung thì locator lấy đảo trái nhất = mũi tên của app nửa TRÁI — đây là lý do " +
                "`locate` không nhận null và `handleArrowByGlyph` bỏ nhịp khi `windowRect == null`",
        )
    }

    // ── (4) PHỦ SÓNG ĐI HẾT ĐƯỜNG: locator → classifyWazeInk → registry ────────────────────────────────

    /**
     * Phủ sóng **tới matcher**, đo end-to-end thay cho proxy lưới 15×15 của test cũ.
     *
     * Con số này là thứ được trích trong KDoc `WazeArrowRegistry.VIETMAP_INK` mục "PHỦ SÓNG THẬT" và bị
     * `WazeArrowRegistryTest.moi con so template ghi trong KDoc phai khop registry that` soi lại — đổi ở đây
     * thì phải đổi cả KDoc, và ngược lại.
     */
    @Test
    fun `phu song toi matcher — bao nhieu maneuver ra duoc ma AMAP`() {
        val classified = LinkedHashMap<String, Int>()
        val unmatched = ArrayList<String>()
        for (n in maneuverNames) {
            val frame = compose(n)
            val r = NavGlyphLocator.locate(frame, window, DPI)
            if (r == null) { unmatched += n; continue }
            val m = ManeuverSignature.classifyWazeInk(crop(frame, r))
            val amap = m.amap
            if (amap == null) unmatched += n else classified[n] = amap
        }
        assertEquals(
            MATCHED_TO_AMAP, classified.size,
            "số maneuver VietMap đi hết đường tới mã AMAP đã đổi.\nRA MÃ: $classified\nTRƯỢT: $unmatched",
        )
        // Mọi ca ra mã phải ra mã ĐÚNG HỌ — sai hướng nguy hiểm hơn im lặng (CLAUDE.md).
        for ((n, amap) in classified) {
            val want = expectedAmap(n)
            if (want != null) assertEquals(want, amap, "$n: ra mã AMAP $amap, mong $want")
        }
    }

    /**
     * CANARY cho B3.52 — **glyph nhiều thành phần không được ra mã AMAP**.
     *
     * [NavGlyphLocator] trả bbox của MỘT đảo, nên với 10 tên dưới đây nó trả về một **mảnh** của glyph, không
     * phải cả glyph ([ĐO] 08-23 vòng 3b, vd `rotary` trả (42,93)-(76,142) 34×49 trong khi hợp mực là
     * (42,82)-(127,167) 85×85; `arrive_left` lấy mảnh TRÁI còn `arrive_right` lấy mảnh PHẢI).
     *
     * Hiện cả 10 đều ra `(không khớp)` (Hamming tới template gần nhất = **32** > ngưỡng 18) nên VÔ HẠI. Nhưng
     * bất biến `≥ 37 bit` chỉ ràng buộc **template↔template**; nó KHÔNG cấm một crop-mảnh rơi vào trong 18
     * bit của một template mới thêm. Test này là cái chuông: thêm template mà một mảnh bỗng ra mã ⇒ đỏ ở đây
     * TRƯỚC khi ai đó nhìn thấy mũi tên sai trên cụm.
     *
     * Sửa đúng (gộp thành phần trước khi chấm) = **B3.52**, không làm ở vòng này vì nó đổi quy ước crop ⇒
     * phải dựng lại template.
     */
    @Test
    fun `glyph nhieu thanh phan chua duoc phep ra ma AMAP (canary B3_52)`() {
        val fragmented = listOf(
            "arrive", "arrive_left", "arrive_right", "arrive_straight",
            "depart", "depart_left", "depart_right", "depart_straight",
            "rotary", "roundabout",
        )
        val leaked = LinkedHashMap<String, String>()
        for (n in fragmented) {
            val f = compose(n)
            val r = NavGlyphLocator.locate(f, window, DPI)
            assertNotNull(r, "$n: fixture đã đổi — không còn dò ra mảnh nào")
            val m = ManeuverSignature.classifyWazeInk(crop(f, r!!))
            if (m.amap != null) leaked[n] = "${m.name}/amap=${m.amap}"
        }
        assertEquals(
            emptyMap<String, String>(), leaked,
            "crop MỘT MẢNH của glyph nhiều thành phần vừa khớp được template ⇒ hướng ra là bốc thăm. " +
                "Hoặc gộp thành phần (B3.52), hoặc bỏ template vừa thêm.",
        )
    }

    /**
     * Họ hướng suy từ TÊN asset (chỉ những họ có ánh xạ AMAP không mập mờ). Đây là canary "sai hướng":
     * một mũi tên rẽ TRÁI mà ra mã rẽ PHẢI thì đỏ ở đây, không cần chờ ai nhìn thấy trên xe.
     *
     * [P2] 08-23 vòng review B3.53 — GỘP về [VietMapGlyphFrames.expectedAmap] thay vì giữ bản sao. Đây là
     * bảng **chân lý về HƯỚNG**, không phải tiện ích nạp file: hai bản lệch nhau một nhánh là hai test
     * khẳng định hai sự thật trái ngược về trái/phải mà không ai đỏ. Bản sao cũ giống hệt bản này khi gộp.
     */
    private fun expectedAmap(name: String): Int? = VietMapGlyphFrames.expectedAmap(name)

    private companion object {
        const val DPI = 240

        /**
         * [ĐO] 08-25 (B3.52) — số maneuver VietMap đi trọn đường `locate → classifyWazeInk → mã AMAP`.
         * Tăng 39 → 63 nhờ 6 template SINH TỪ FIXTURE RUNTIME (glyph banner lệch template SVG 19–31 bit dù
         * cùng họ hướng ⇒ trước đó MISS). Vẫn < 87 vì: 10 glyph nhiều-thành-phần (canary), 2 glyph slight bị
         * loại vì biên <37 bit với lớp khác, họ vòng-xuyến-phải + rotary/roundabout nét-xám chưa tách được.
         */
        const val MATCHED_TO_AMAP = 63
    }
}
