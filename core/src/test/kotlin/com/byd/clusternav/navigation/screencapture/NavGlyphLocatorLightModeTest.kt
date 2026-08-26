package com.byd.clusternav.navigation.screencapture

import com.byd.clusternav.navigation.ArrayPixelFrame
import com.byd.clusternav.navigation.ManeuverSignature
import com.byd.clusternav.navigation.PixelFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO

/**
 * F4c (owner 2026-08-24) — locator phải phủ CẢ light mode LẪN dark mode.
 *
 * Gốc [ĐO]: app dẫn ở day/light theme vẽ mũi tên TỐI trên nền SÁNG; [NavGlyphLocator.locate] chỉ dò
 * sáng-trên-tối ⇒ trả null ⇒ "Waze never appears / VietMap dark ban ngày". [NavGlyphLocator.locateAny] phủ
 * nốt bằng cách đảo màu (light mode). Test dùng ảnh Waze THẬT dark, đảo màu để mô phỏng light theme.
 */
class NavGlyphLocatorLightModeTest {

    /** Waze rẽ thật, dark theme (mực trắng trên nền tối) — đã biết classify được (probe 2026-08-24). */
    private val res = "/diagnostics/glyph/w1920h720-d240.png"

    private fun darkFrame(): Pair<PixelFrame, CropRect> {
        val url = requireNotNull(javaClass.getResource(res)) { "fixture $res missing" }
        val img = ImageIO.read(url)
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return ArrayPixelFrame(img.width, img.height, px) to CropRect(0, 0, img.width, img.height)
    }

    private fun classify(frame: PixelFrame, loc: NavGlyphLocator.Located): String {
        val raw = requireNotNull(PixelFrameOps.crop(frame, loc.rect)) { "crop null" }
        val crop = if (loc.inverted) requireNotNull(PixelFrameOps.invert(raw)) else raw
        return ManeuverSignature.classifyWazeInk(crop).name
    }

    @Test fun `dark mode — locateAny do thang, inverted=false, classify duoc`() {
        val (frame, win) = darkFrame()
        val loc = NavGlyphLocator.locateAny(frame, win, 240)
        assertNotNull(loc)
        assertFalse(loc!!.inverted, "dark mode dò thẳng, KHÔNG cần đảo màu")
        assertTrue(classify(frame, loc).startsWith("maneuver_turn"), "dark classify được")
    }

    @Test fun `light mode (dao mau) — 1-cuc GAY nhung locateAny RA, cung maneuver`() {
        val (dark, win) = darkFrame()
        val darkName = classify(dark, NavGlyphLocator.locateAny(dark, win, 240)!!)

        // Mô phỏng light/day theme = đảo màu toàn khung (mũi tên tối trên nền sáng).
        val light = requireNotNull(PixelFrameOps.invert(dark))

        // (a) locate 1-CỰC (sáng-trên-tối) PHẢI trả null ở light mode — CHÍNH là cái bug owner chỉ.
        assertNull(NavGlyphLocator.locate(light, win, 240), "locate 1-cực gãy ở light mode = đúng bug")

        // (b) locateAny PHẢI phủ được + báo inverted=true + ra CÙNG maneuver với dark.
        val lightLoc = NavGlyphLocator.locateAny(light, win, 240)
        assertNotNull(lightLoc, "locateAny PHẢI ra ở light mode")
        assertTrue(lightLoc!!.inverted, "light mode ⇒ inverted=true")
        assertEquals(darkName, classify(light, lightLoc), "cùng mũi tên ⇒ cùng maneuver bất kể theme")
    }

    @Test fun `dark mode khong mui ten — KHONG dao mau (chan duong tinh gia)`() {
        // Khung TỐI đặc (RGB 20,20,20), KHÔNG mũi tên: cực 1 trả null; guard isLightBanner thấy nền tối ⇒
        // KHÔNG thử cực đảo ⇒ locateAny null. Nếu bỏ guard, đảo màu khung tối-giữa-hai-khúc có thể vồ đảo
        // sáng giả từ nhiễu bản đồ → khớp nhầm template → SAI hướng. Đây là cổng an toàn im-lặng>sai-hướng.
        val w = 1920; val h = 720
        val dark = IntArray(w * h) { -0x1000000 or (20 shl 16) or (20 shl 8) or 20 }
        val loc = NavGlyphLocator.locateAny(ArrayPixelFrame(w, h, dark), CropRect(0, 0, w, h), 240)
        assertNull(loc, "dark mode không mũi tên ⇒ KHÔNG đảo màu ⇒ null (không dương-tính-giả)")
    }

    @Test fun `dark mode + thanh trang thai SANG mong — VAN khong dao mau (bien isLightBanner)`() {
        // Review 2026-08-25 hỏi thẳng: dark theme + status bar SÁNG ở đỉnh ROI có kéo mean ROI qua 128 làm
        // [locateAny] đảo màu NHẦM (→ có thể vồ đảo sáng giả → SAI hướng) không? Dựng khung TỐI (luma 30) +
        // dải trắng cao 40px ở đỉnh (status bar), KHÔNG mũi tên. ROI @1920×720,dpi240 cao 324px ⇒ tỉ lệ sáng
        // 40/324 ≈ 0.123 ⇒ mean ≈ 0.123·255 + 0.877·30 ≈ 58 < 128 ⇒ isLightBanner=false ⇒ KHÔNG đảo màu.
        // Điểm lật: cần > 43.5% ROI sáng mới qua 128 (0.30 vẫn chỉ ~98) — status bar mỏng còn xa mốc đó ⇒
        // biên dark-rejection rộng. Đây là cổng an toàn im-lặng>sai-hướng, khoá lại để ai chỉnh LIGHT_BG_MEAN
        // biết mình đang ăn vào biên nào.
        val w = 1920; val h = 720
        val px = IntArray(w * h) { i ->
            if (i / w < 40) (-0x1000000 or (255 shl 16) or (255 shl 8) or 255)   // status bar trắng
            else (-0x1000000 or (30 shl 16) or (30 shl 8) or 30)                 // nền tối, không mũi tên
        }
        val loc = NavGlyphLocator.locateAny(ArrayPixelFrame(w, h, px), CropRect(0, 0, w, h), 240)
        assertNull(loc, "dark + status bar sáng mỏng ⇒ mean ROI vẫn < 128 ⇒ KHÔNG đảo màu ⇒ null")
    }
}
