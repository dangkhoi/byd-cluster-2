package com.byd.clusternav.navigation.screencapture

import com.byd.clusternav.navigation.ArrayPixelFrame
import com.byd.clusternav.navigation.ManeuverRegistry
import com.byd.clusternav.navigation.ManeuverSignature
import com.byd.clusternav.navigation.PixelFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá logic THUẦN của [CaptureRouter] off-car (V-unit, spec §4.3/§4.4): chọn 1/4 case + tính crop bounds
 * theo 3 tầng, gồm gate đóng và offset nửa Case-2. Không cần thiết bị.
 */
class CaptureRouterTest {

    private val geom = DisplayGeometry(displayW = 1920, displayH = 720)   // main default; cluster id = 1

    private fun loc(
        pkg: String = "com.waze",
        displayId: Int = 0,
        fullscreen: Boolean = true,
        slot: CaptureSlotSide? = null,
        leftPercent: Int = 50,
        foreground: Boolean = true,
        navFresh: Boolean = true,
    ) = AppLocation(pkg, displayId, fullscreen, slot, leftPercent, foreground, navFresh)

    // ── (a) Chọn case — 4 case + biên ────────────────────────────────────────────────

    @Test
    fun `Case 1 — full man chinh`() {
        assertEquals(CaptureCase.FULL_MAIN, CaptureRouter.selectCase(loc(displayId = 0, fullscreen = true), geom))
    }

    @Test
    fun `Case 2 — nua man chinh split`() {
        assertEquals(
            CaptureCase.HALF_MAIN_SPLIT,
            CaptureRouter.selectCase(loc(displayId = 0, fullscreen = false, slot = CaptureSlotSide.LEFT), geom),
        )
    }

    @Test
    fun `Case 3 — ben cum (display 1)`() {
        assertEquals(CaptureCase.CLUSTER_CAST, CaptureRouter.selectCase(loc(displayId = 1), geom))
    }

    @Test
    fun `Case 4 — nav tuoi nhung app khong foreground`() {
        assertEquals(CaptureCase.NOT_ACTIVE, CaptureRouter.selectCase(loc(foreground = false), geom))
    }

    @Test
    fun `bien — foreground tren display phu khac cum coi nhu cast`() {
        assertEquals(CaptureCase.CLUSTER_CAST, CaptureRouter.selectCase(loc(displayId = 2), geom))
    }

    @Test
    fun `foreground=false thang the tren MOI displayId — luon NOT_ACTIVE`() {
        assertEquals(CaptureCase.NOT_ACTIVE, CaptureRouter.selectCase(loc(displayId = 1, foreground = false), geom))
        assertEquals(CaptureCase.NOT_ACTIVE, CaptureRouter.selectCase(loc(displayId = 0, foreground = false), geom))
    }

    // ── GATE (V-gate) ────────────────────────────────────────────────────────────────

    @Test
    fun `gate dong — navFresh=false thi route tra null (KHONG capture)`() {
        assertNull(CaptureRouter.route(loc(navFresh = false), geom))
    }

    @Test
    fun `gate mo — navFresh=true thi route KHONG null`() {
        assertNotNull(CaptureRouter.route(loc(navFresh = true), geom))
    }

    // ── (b) Bounds 3 tầng ──────────────────────────────────────────────────────────────

    @Test
    fun `bounds tang 2 — a11y null thi dung rect co dinh (Waze arrow OpenBYD)`() {
        val plan = CaptureRouter.route(loc(pkg = "com.waze", fullscreen = true), geom, a11y = null)!!
        assertEquals(BoundsSource.FIXED_CALIBRATED, plan.boundsSource)
        assertEquals(CaptureCalibration.WAZE_ARROW_OPENBYD, plan.bounds)
        assertEquals(CaptureTarget.ARROW, plan.target)
    }

    @Test
    fun `bounds tang 1 — a11y tuoi thi thang rect co dinh`() {
        val a11yRect = CropRect(100, 100, 200, 180)
        val plan = CaptureRouter.route(
            loc(fullscreen = true), geom,
            a11y = CaptureBounds(a11yRect, capturedAtMs = 1_000L),
            now = 1_200L, freshMs = 1500L,
        )!!
        assertEquals(BoundsSource.A11Y_DYNAMIC, plan.boundsSource)
        assertEquals(a11yRect, plan.bounds)
    }

    @Test
    fun `bounds — a11y CU (qua freshMs) thi roi ve tang 2 co dinh`() {
        val plan = CaptureRouter.route(
            loc(pkg = "com.waze", fullscreen = true), geom,
            a11y = CaptureBounds(CropRect(100, 100, 200, 180), capturedAtMs = 0L),
            now = 5_000L, freshMs = 1500L,
        )!!
        assertEquals(BoundsSource.FIXED_CALIBRATED, plan.boundsSource)
        assertEquals(CaptureCalibration.WAZE_ARROW_OPENBYD, plan.bounds)
    }

    @Test
    fun `target — VietMap ra CAMERA, con lai ra ARROW`() {
        assertEquals(CaptureTarget.CAMERA, CaptureRouter.route(loc(pkg = "vn.vietmap.live"), geom)!!.target)
        assertEquals(CaptureTarget.ARROW, CaptureRouter.route(loc(pkg = "com.waze"), geom)!!.target)
    }

    // ── Case-2 offset nửa L/R ──────────────────────────────────────────────────────────

    @Test
    fun `Case 2 offset — nua PHAI dich rect co dinh +W_leftPercent_100`() {
        val plan = CaptureRouter.route(
            loc(pkg = "com.waze", fullscreen = false, slot = CaptureSlotSide.RIGHT, leftPercent = 50), geom,
        )!!
        assertEquals(CaptureCase.HALF_MAIN_SPLIT, plan.case)
        // divider = 1920*50/100 = 960; rect (26,218,208,298) -> (986,218,1168,298), clamp vao [960,1920) giu nguyen.
        assertEquals(CropRect(986, 218, 1168, 298), plan.bounds)
        assertEquals(BoundsSource.FIXED_CALIBRATED, plan.boundsSource)
    }

    @Test
    fun `Case 2 offset — nua TRAI khong dich, clamp vao 0 toi divider`() {
        val plan = CaptureRouter.route(
            loc(pkg = "com.waze", fullscreen = false, slot = CaptureSlotSide.LEFT, leftPercent = 50), geom,
        )!!
        assertEquals(CropRect(26, 218, 208, 298), plan.bounds)   // trong [0,960) → giữ nguyên
    }

    @Test
    fun `Case 2 clamp — a11y tran sang nua kia bi cat ve nua cua app (phai)`() {
        // RIGHT slot, divider 960; a11y rect (900,200,1100,300) tràn sang nửa trái → clamp về [960,1100).
        val plan = CaptureRouter.route(
            loc(fullscreen = false, slot = CaptureSlotSide.RIGHT, leftPercent = 50), geom,
            a11y = CaptureBounds(CropRect(900, 200, 1100, 300), capturedAtMs = 10L),
            now = 20L,
        )!!
        assertEquals(BoundsSource.A11Y_DYNAMIC, plan.boundsSource)
        assertEquals(CropRect(960, 200, 1100, 300), plan.bounds)
    }

    @Test
    fun `halfOffsetX — chi ap cho nua PHAI`() {
        assertEquals(0, CaptureRouter.halfOffsetX(CaptureCase.HALF_MAIN_SPLIT, loc(slot = CaptureSlotSide.LEFT, leftPercent = 40), geom))
        assertEquals(768, CaptureRouter.halfOffsetX(CaptureCase.HALF_MAIN_SPLIT, loc(slot = CaptureSlotSide.RIGHT, leftPercent = 40), geom))
        assertEquals(0, CaptureRouter.halfOffsetX(CaptureCase.FULL_MAIN, loc(slot = CaptureSlotSide.RIGHT, leftPercent = 40), geom))
    }

    // ── crop → PixelFrame → classify (tái dùng ManeuverSignature, KHÔNG viết lại) ──────

    /**
     * Xác nhận CHUỖI THUẦN: full frame → route bounds (a11y trỏ đúng glyph) → [PixelFrameOps.crop] →
     * [ManeuverSignature.classify]. Nhúng glyph 15×15 của "turn_normal_left" vào frame lớn, a11y bounds trỏ
     * đúng ô đó → classify phải ra 2 (rẽ trái AMAP), y như [ManeuverSignatureTest].
     */
    @Test
    fun `crop-classify — arrow Waze qua router-crop van ra dung ma (reuse ManeuverSignature)`() {
        val bits = ManeuverRegistry.RAW.first { it.second == "maneuver_turn_normal_left" }.first
        val g = 15
        val ox = 26
        val oy = 218
        val screenW = 400
        val screenH = 480
        val screen: PixelFrame = ArrayPixelFrame(screenW, screenH, IntArray(screenW * screenH) { idx ->
            val x = idx % screenW
            val y = idx / screenW
            val cx = x - ox
            val cy = y - oy
            if (cx in 0 until g && cy in 0 until g && bits[cy * g + cx] == '1') 0xFFFFFFFF.toInt() else 0x00000000
        })
        val plan = CaptureRouter.route(
            loc(pkg = "com.waze", fullscreen = true),
            DisplayGeometry(screenW, screenH),
            a11y = CaptureBounds(CropRect(ox, oy, ox + g, oy + g), capturedAtMs = 5L),
            now = 10L,
        )!!
        assertEquals(BoundsSource.A11Y_DYNAMIC, plan.boundsSource)
        val crop = PixelFrameOps.crop(screen, plan.bounds)
        assertNotNull(crop)
        assertEquals(g, crop!!.width)
        assertEquals(g, crop.height)
        assertEquals(2, ManeuverSignature.classify(crop))   // 2 = rẽ trái (khớp ManeuverSignatureTest)
    }

    @Test
    fun `crop — rect ngoai khung tra null`() {
        val f: PixelFrame = ArrayPixelFrame(10, 10, IntArray(100))
        assertNull(PixelFrameOps.crop(f, CropRect(20, 20, 30, 30)))
    }

    // ── CropRect helpers ────────────────────────────────────────────────────────────

    @Test
    fun `CropRect — clampTo giao rong tra isEmpty`() {
        assertTrue(CropRect(0, 0, 10, 10).clampTo(CropRect(50, 50, 60, 60)).isEmpty())
        assertFalse(CropRect(0, 0, 10, 10).clampTo(CropRect(5, 5, 60, 60)).isEmpty())
    }
}
