package com.byd.clusternav.navigation

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B3.6 (spec `waze-vietmap-screen-capture.html` §4.5): khoá off-car cơ CHẾ nạp template mũi tên Waze/VietMap.
 *
 * [ManeuverRegistry].RAW (38 mục GMaps) KHÔNG khớp glyph Waze (Hamming>18, NCC<0.45) → `classify*` trả null dù
 * crop đúng. Test này chứng minh cơ chế (KHÔNG phải dữ liệu thật): (1) [ManeuverSignature.signatureBits] rút
 * chuỗi 225-bit ĐÚNG format RAW; (2) nạp chuỗi đó vào [WazeArrowRegistry] rồi classify CHÍNH khung ⇒ khớp
 * (Hamming=0); (3) registry rỗng ⇒ 38 mục AMAP KHÔNG đổi.
 *
 * ⚠ Template Waze THẬT thu trên emulator/xe sau (KHÔNG bịa) — ở đây chỉ dùng chữ ký TỔNG HỢP để khoá cơ chế.
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

    // ── (2) nạp template Waze → classify khớp (Hamming=0) ─────────────────────────────────

    @Test
    fun `nap template Waze roi classify CHINH khung ra ma AMAP cua ten do`() {
        val glyph = wazeLikeGlyph()
        val bits = ManeuverSignature.signatureBits(glyph)!!
        // Nạp dưới tên "turn_normal_right" → classify ra AMAP 3 (rẽ phải) qua registry Waze. Bảo đảm bởi
        // short-circuit Hamming=0: khung chính có d==0 với template vừa nạp → thắng mọi near-match GMaps.
        WazeArrowRegistry.register(bits, "maneuver_turn_normal_right")
        assertEquals(3, ManeuverSignature.classify(glyph))
        assertEquals(2, ManeuverSignature.classifyHal(glyph))    // HAL: 2 = rẽ phải
    }

    @Test
    fun `nap template Waze ten vong xuyen roi classifyManeuver ra Maneuver co huong`() {
        val glyph = wazeLikeGlyph()
        val bits = ManeuverSignature.signatureBits(glyph)!!
        WazeArrowRegistry.register(bits, "maneuver_roundabout_enter_and_exit_ccw_normal_left")
        assertEquals(Maneuver.ROUNDABOUT_LEFT, ManeuverSignature.classifyManeuver(glyph))
    }

    @Test
    fun `template Waze them runtime duoc nhan ngay (version bump, khong ket dinh lazy)`() {
        val glyph = wazeLikeGlyph()
        val bits = ManeuverSignature.signatureBits(glyph)!!
        WazeArrowRegistry.register(bits, "maneuver_turn_normal_left")
        assertEquals(2, ManeuverSignature.classify(glyph))       // thấy NGAY sau register (không kẹt cache lazy)
        // Nạp lại dưới tên khác → cache đóng-gói-lại theo version → mã đổi theo.
        WazeArrowRegistry.load(listOf(bits to "maneuver_turn_normal_right"))
        assertEquals(3, ManeuverSignature.classify(glyph))
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

    @Test
    fun `registry Waze rong — 38 muc AMAP van classify dung (khong regression)`() {
        // Dựng khung TỪ chữ ký registry GMaps (Hamming=0) → phải ra đúng mã, không bị Waze registry chen.
        val bits = ManeuverRegistry.RAW.first { it.second == "maneuver_turn_normal_left" }.first
        val g = 15
        val f = ArrayPixelFrame(g, g, IntArray(g * g) { i -> if (bits[i] == '1') 0xFFFFFFFF.toInt() else 0x00000000 })
        assertEquals(2, ManeuverSignature.classify(f))           // 2 = rẽ trái (như ManeuverSignatureTest canary)
    }
}
