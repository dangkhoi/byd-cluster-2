package com.byd.clusternav

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B-III — test QUÉT SOURCE khoá ba thứ mà unit-test hành vi không nhìn thấy được:
 *
 *  1. CLAUDE.md §8 — hàm mới phải có **call site thật**. `TurnDistancePlausibility` compile sạch, có KDoc, mà
 *     không ai gọi thì cụm vẫn chạy y như cũ và không test nào đỏ (đúng ca `CastShell.evictVd` trong §8).
 *  2. Cạm bẫy `SpeedProvider.mps()` — biến thể last-good trả **0.0 khi HAL câm** (SpeedProvider.kt:26-35).
 *     Dùng nhầm nó thì guard tưởng xe LUÔN ĐỖ ⇒ luật đóng-băng chết câm, không ai biết.
 *  3. Ranh giới sở hữu session-latch (khuôn `PhysicalHudOwnershipTest`) — `blankNavDistance` là CONTENT-only.
 */
class NavOutputGuardWiringTest {

    private val owner = SourceRoots.text("src/main/java/com/byd/clusternav/NavOutputOwner.kt")
    private val hal = SourceRoots.text("src/main/java/com/byd/clusternav/modules/hal/BydHal.kt")

    /** Thân hàm `fun <name>(...)` ở cấp 4-space trong BydHal (dừng ở dấu đóng ngoặc `\n    }` đầu tiên). */
    private fun halFunctionBody(name: String): String {
        val start = hal.indexOf("fun $name(")
        assertTrue(start >= 0, "không tìm thấy fun $name trong BydHal.kt")
        val rest = hal.substring(start)
        val end = rest.indexOf("\n    }")
        assertTrue(end > 0, "không tìm thấy dấu đóng của fun $name")
        return rest.substring(0, end)
    }

    /** T27 — mọi mắt xích B-III phải có call site THẬT trong owner, không chỉ tồn tại trên đĩa. */
    @Test
    fun `guard co call site that trong NavOutputOwner`() {
        assertTrue(owner.contains("TurnDistancePlausibility"), "owner phải nhận guard qua ctor")
        assertTrue(owner.contains("guard.accept("), "phải có đường xét mẫu")
        assertTrue(owner.contains("guard.noSample()"), "phải có đường 'không có mẫu' (VietMap/GMaps)")
        assertTrue(owner.contains("guard.reset()"), "phải reset guard theo vòng đời frame")
        assertTrue(owner.contains("NavViewIdSource.freshReadingFor("), "phải đọc ảnh chụp NHẤT QUÁN, không đọc rời")
        assertTrue(owner.contains("sink.blankDistance()"), "phải có đường XOÁ ô cự-ly (cạnh xuống)")
        assertTrue(owner.contains("BydHal.blankNavDistance("), "HalSink phải nối xuống HAL thật")
        assertTrue(owner.contains("SpeedProvider.mpsOrNull"), "tốc độ phải đi qua biến thể phân biệt được 'không đọc được'")
    }

    /**
     * T28 — CẤM `SpeedProvider.mps()`. `mpsOrNull()` trả null khi không đọc được ⇒ guard tắt luật đóng-băng;
     * `mps()` trả 0.0 ⇒ guard kết luận "xe đang đỗ" và im lặng vĩnh viễn. Hai hành vi khác hẳn nhau.
     */
    @Test
    fun `NavOutputOwner KHONG dung bien the last-good cua SpeedProvider`() {
        assertFalse(owner.contains("SpeedProvider.mps("), "phải dùng mpsOrNull(), không dùng biến thể last-good")
    }

    /**
     * T30 (08-22 vòng 2) — tốc độ phải truyền dạng **LAMBDA**, không phải giá trị đã đánh giá.
     *
     * `guard.accept(..., speed())` là một cái bẫy im lặng: Kotlin đánh giá tham số TRƯỚC khi vào hàm, nên
     * `SpeedProvider.mpsOrNull()` (reflection xuống HAL) chạy ở MỌI tick 4 Hz — kể cả những tick mà guard sẽ
     * trả [TurnDistancePlausibility.Verdict.REPEAT] ngay dòng đầu (a11y chỉ publish ~1,25 Hz nên đó là đa số).
     * Kết quả không sai, nhưng đốt ~3 lời gọi HAL/giây vô ích suốt cả chuyến. Hành vi LƯỜI đã khoá off-car ở
     * `TurnDistancePlausibilityTest`; ở đây chỉ khoá đúng cái call site.
     */
    @Test
    fun `owner truyen speed dang lambda, khong danh gia som`() {
        assertTrue(owner.contains("r.atMs, speed)"), "phải truyền chính lambda `speed`")
        assertFalse(owner.contains("r.atMs, speed())"), "KHÔNG được đánh giá speed() ở call site")
    }

    /**
     * T29 — `blankNavDistance` chỉ được chạm HAI register cự-ly. Chạm session latch = hạ phiên HUD giữa lúc
     * đang lái (latch là độc quyền của [NavigationHudOwner], xem `PhysicalHudOwnershipTest`).
     */
    @Test
    fun `blankNavDistance chi ghi o cu-ly, khong cham session latch`() {
        val body = halFunctionBody("blankNavDistance")
        assertTrue(body.contains("INSTRUMENT_FRONT_CROSSING_DISTANCE_SET"), "phải ghi register cự-ly domestic")
        assertTrue(
            body.contains("INSTRUMENT_DISTANCE_TARGET_HEAD_SET") && body.contains("CROSSING_DIST_OVERSEA_ID"),
            "phải ghi cả nhánh oversea (tên reflect, fallback raw-id) như pushNavigation",
        )
        assertFalse(body.contains("SEND_NAVI_STATUS"), "KHÔNG chạm session latch")
        assertFalse(body.contains("SET_NAVI_SCREEN_STATUS"), "KHÔNG chạm session latch")
        assertFalse(body.contains("GUIDE_INFO_SIMPLE"), "KHÔNG chạm icon mũi tên — hướng vẫn đáng tin")
        assertFalse(owner.contains("BydHal.writeNavFrame"), "owner nguồn ẢNH không bao giờ đi đường session latch")
    }
}
