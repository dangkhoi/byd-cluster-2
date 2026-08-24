package com.byd.clusternav.navigation

import com.byd.clusternav.navigation.screencapture.ArrowSample
import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * F4 (spec `docs/specs/nav-input-output-architecture.html`) — khoá [NavContentBuilder] bằng GIÁ TRỊ, off-car.
 *
 * Hai nhóm:
 *  1. **HÀNH VI** — `fromImage` dựng khung đúng, và bất biến MỘT-PACKAGE-MỘT-KHUNG được thi hành TRONG builder
 *     (không chỉ ở owner): mẫu a11y của app khác ⇒ bỏ HẾT, không ghép nửa nọ nửa kia.
 *  2. **DỜI CHỖ, KHÔNG VIẾT LẠI** — quét source `fromNotification` để chắc rằng biểu thức của đường Google Maps
 *     đi qua đúng những mảnh cũ, không có nhánh mới lén vào (CLAUDE.md §6). Bảng vàng ở
 *     `GmapsContentGoldenTest` khoá phần giá trị.
 */
class NavContentBuilderTest {

    private val waze = "com.waze"
    private val vietmap = "vn.vietmap.live"

    private fun arrow(pkg: String, maneuver: Maneuver?, amap: Int?) =
        ArrowSample(pkg = pkg, maneuver = maneuver, amap = amap, atMs = 1_000L)

    private fun reading(
        pkg: String,
        turnMeters: Int = 140,
        road: String = "Quang Trung",
        arrivalClock: String = "18:21",
        routeSeconds: Int = 900,
        routeMeters: Int = 5_200,
    ) = NavViewIdSource.Reading(pkg, turnMeters, road, 1_000L, arrivalClock, routeSeconds, routeMeters)

    // ── 1. fromImage — hành vi ────────────────────────────────────────────────────────────────────────

    @Test fun `fromImage - mau a11y CUNG goi thi khung mang du cu ly + duong + ETA`() {
        val c = NavContentBuilder.fromImage(waze, arrow(waze, Maneuver.TURN_LEFT, 2), reading(waze), 140)!!
        assertEquals(Maneuver.TURN_LEFT, c.maneuver)
        assertEquals(2, c.maneuverCode)
        assertEquals(140, c.distanceMeters)
        assertEquals("Quang Trung", c.roadName)
        assertEquals("18:21", c.arrivalClock)
        assertEquals(900, c.routeRemainingSeconds)
        assertEquals(5_200, c.routeRemainingMeters)
        assertNull(c.maneuverText, "nguồn ảnh không có dòng lệnh rẽ — xem KDoc fromImage")
    }

    /**
     * BẤT BIẾN MỘT-PACKAGE-MỘT-KHUNG. Khung vẫn được dựng (mũi tên còn đáng tin) nhưng MỌI số liệu của app
     * khác bị bỏ — không bao giờ để cự-ly/tên đường của VietMap đứng cạnh mũi tên của Waze.
     */
    @Test fun `fromImage - mau a11y LECH goi thi bo HET so lieu, van giu mui ten`() {
        val c = NavContentBuilder.fromImage(waze, arrow(waze, Maneuver.TURN_RIGHT, 3), reading(vietmap), -1)!!
        assertEquals(Maneuver.TURN_RIGHT, c.maneuver)
        assertNull(c.distanceMeters)
        assertNull(c.roadName)
        assertNull(c.arrivalClock)
        assertNull(c.routeRemainingSeconds)
        assertNull(c.routeRemainingMeters)
    }

    @Test fun `fromImage - khong co mau a11y thi chi con mui ten`() {
        val c = NavContentBuilder.fromImage(vietmap, arrow(vietmap, Maneuver.STRAIGHT, 9), null, -1)!!
        assertEquals(Maneuver.STRAIGHT, c.maneuver)
        assertNull(c.distanceMeters)
        assertNull(c.roadName)
    }

    @Test fun `fromImage - maneuver null thi suy tu ma AMAP`() {
        val c = NavContentBuilder.fromImage(waze, arrow(waze, null, 3), null, -1)!!
        assertEquals(Maneuver.TURN_RIGHT, c.maneuver)
        assertEquals(3, c.maneuverCode)
    }

    /** Degrade-safe: không suy được hướng ⇒ KHÔNG dựng khung (thà không hiện còn hơn hiện mũi tên bịa). */
    @Test fun `fromImage - khong suy duoc huong thi tra null`() {
        assertNull(NavContentBuilder.fromImage(waze, arrow(waze, null, null), reading(waze), 140))
        assertNull(NavContentBuilder.fromImage(waze, arrow(waze, null, 999), reading(waze), 140))
        assertNull(NavContentBuilder.fromImage(waze, null, reading(waze), 140))
    }

    /** Guard cự-ly từ chối ⇒ -1 ⇒ `distanceMeters = null` ⇒ hạ nguồn ghi -1 = XOÁ TRẮNG ô cự-ly. */
    @Test fun `fromImage - cu ly am thi distanceMeters null`() {
        val c = NavContentBuilder.fromImage(waze, arrow(waze, Maneuver.TURN_LEFT, 2), reading(waze), -1)!!
        assertNull(c.distanceMeters)
        assertEquals("Quang Trung", c.roadName, "guard chỉ gác ô cự-ly, KHÔNG gác tên đường")
    }

    @Test fun `fromImage - cu ly 0 met van la gia tri that`() {
        val c = NavContentBuilder.fromImage(waze, arrow(waze, Maneuver.TURN_LEFT, 2), reading(waze), 0)!!
        assertEquals(0, c.distanceMeters)
    }

    /**
     * `NavigationFrameContent.init` NÉM nếu `arrivalClock` không khớp `\d{1,2}:\d{2}`. Nguồn a11y là chuỗi
     * đọc từ màn hình ⇒ phải lọc, không được tin. Ném ở đây là 4 exception/giây trên luồng owner.
     */
    @Test fun `fromImage - gio toi rac thi bo im lang, khong nem`() {
        val c = NavContentBuilder.fromImage(
            waze, arrow(waze, Maneuver.TURN_LEFT, 2), reading(waze, arrivalClock = "khong ro"), 140,
        )!!
        assertNull(c.arrivalClock)
    }

    @Test fun `fromImage - truong rong hoac am deu ve null`() {
        val c = NavContentBuilder.fromImage(
            waze,
            arrow(waze, Maneuver.TURN_LEFT, 2),
            reading(waze, road = "   ", arrivalClock = "", routeSeconds = -1, routeMeters = -1),
            140,
        )!!
        assertNull(c.roadName)
        assertNull(c.arrivalClock)
        assertNull(c.routeRemainingSeconds)
        assertNull(c.routeRemainingMeters)
    }

    // ── 2. fromNotification — hành vi tối thiểu (bảng vàng lo phần còn lại) ───────────────────────────

    @Test fun `fromNotification - khung GMaps dien hinh`() {
        val c = NavContentBuilder.fromNotification(
            maneuverIcon = 3, maneuverText = "", distance = "250 m", road = "Nguyễn Huệ",
            eta = "10:32 · 5.2 km · 8 phút", maneuver = null,
        )
        assertEquals(3, c.maneuverCode)
        assertEquals(250, c.distanceMeters)
        assertEquals("Nguyễn Huệ", c.roadName)
        assertEquals("10:32", c.arrivalClock)
        assertEquals(5_200, c.routeRemainingMeters)
        assertEquals(8 * 60, c.routeRemainingSeconds)
        assertEquals(Maneuver.TURN_RIGHT, c.maneuver)
    }

    @Test fun `fromNotification - maneuver co san thang ma AMAP`() {
        val c = NavContentBuilder.fromNotification(
            maneuverIcon = 11, maneuverText = "lối ra thứ 2", distance = "300 m", road = "Điện Biên Phủ",
            eta = "", maneuver = Maneuver.ROUNDABOUT_RIGHT,
        )
        assertEquals(Maneuver.ROUNDABOUT_RIGHT, c.maneuver, "maneuver của nguồn thắng fromAmapIcon(11)")
        assertEquals("lối ra thứ 2", c.maneuverText)
    }

    // ── 3. DỜI CHỖ, KHÔNG VIẾT LẠI (quét source) ─────────────────────────────────────────────────────

    private val src by lazy {
        SourceRoots.text("src/main/kotlin/com/byd/clusternav/navigation/NavContentBuilder.kt")
    }

    /** Thân `fun fromNotification(...)` (dừng ở dấu `)` đóng ở cột 0 của biểu thức `= NavigationFrameContent(`). */
    private fun fromNotificationBody(): String {
        val start = src.indexOf("fun fromNotification(")
        assertTrue(start >= 0, "không tìm thấy fun fromNotification")
        val rest = src.substring(start)
        val end = rest.indexOf("\n    )\n")   // dấu đóng của biểu thức, KHÔNG phải của danh sách tham số
        assertTrue(end > 0, "không tìm thấy dấu đóng của fromNotification")
        return rest.substring(0, end)
    }

    /**
     * KHOÁ §6 — đường Google Maps là phép DỜI CHỖ. Mỗi mảnh dưới đây là nguyên văn của bản đang chạy ngoài
     * hiện trường (`NavRepository.ingest` trước 08-24). Thiếu một mảnh = có người đã viết lại một nhánh.
     */
    @Test fun `fromNotification giu NGUYEN VAN cac manh cua ban cu`() {
        val body = fromNotificationBody()
        listOf(
            "maneuverIcon.takeIf { it >= 0 }",
            "maneuverText.takeIf(String::isNotBlank)",
            "NavParse.parseMeters(distance).takeIf { it >= 0 }",
            "road.takeIf(String::isNotBlank)",
            "etaEpochMs = null",
            "NavParse.parseEta(eta).first.takeIf { it >= 0 }",
            "NavParse.parseEta(eta).second.takeIf { it >= 0 }",
            "NavParse.extractArrivalClock(eta)",
            "maneuver = maneuver ?: Maneuver.fromAmapIcon(maneuverIcon)",
        ).forEach { piece ->
            assertTrue(body.contains(piece), "fromNotification phải giữ nguyên mảnh cũ: $piece")
        }
    }

    /** Không nhánh mới: đường notification không được rẽ theo tên gói hay theo bất kỳ điều kiện nào. */
    @Test fun `fromNotification khong co nhanh dieu kien nao`() {
        val body = fromNotificationBody()
        assertFalse(body.contains("if ("), "fromNotification phải là MỘT biểu thức, không nhánh")
        assertFalse(body.contains("when ("), "fromNotification phải là MỘT biểu thức, không nhánh")
        assertFalse(src.contains("import android"), ":core là JVM thuần")
    }
}
