package com.byd.clusternav.navigation

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Khoá [NavViewIdSource] — nguồn nav đọc bằng **view-id a11y** (clone OpenBYD), thu 2026-08-22.
 *
 * Giá trị trong test lấy nguyên văn từ phép đo thật trên WazeMod đang dẫn:
 * `navBarDistance='140 m'` · `navBarStreetLine='Quang Trung'` · `lblArrivalTime='5:50 PM'` ·
 * `lblTimeToDestination='6 min'` · `lblDistanceToDestination='1.2 km'`.
 */
class NavViewIdSourceTest {

    private val waze = "com.chisadin.wazemod"

    @BeforeEach fun setup() = NavViewIdSource.clear()
    @AfterEach fun teardown() = NavViewIdSource.clear()

    /**
     * 08-22: đọc qua ẢNH CHỤP composite chứ không qua field thô — bảy field đã chuyển `private` vì không
     * consumer production nào đọc thô, và đọc thô chính là khe cho phép ghép `road` của mẫu N với
     * `turnMeters` của mẫu N+1 (đọc rách). Test bám đúng hợp đồng công khai.
     */
    @Test
    fun `publish roi doc lai dung tung truong`() {
        NavViewIdSource.publish(waze, 140, "Quang Trung", "5:50 PM", 360, 1200, now = 1_000L)
        val r = NavViewIdSource.freshReadingFor(waze, 1_100L)!!
        assertEquals(waze, r.pkg)
        assertEquals(140, r.turnMeters)
        assertEquals("Quang Trung", r.road)
        assertEquals("5:50 PM", r.arrivalClock)
        assertEquals(360, r.routeSeconds)
        assertEquals(1200, r.routeMeters)
    }

    /**
     * Cự ly chỉ được dùng cho ĐÚNG app đang publish mũi tên — nếu không, cự ly của Waze sẽ dán nhầm vào
     * khung của VietMap (hai app có thể cùng chạy, một trên màn chính một trên cụm).
     */
    @Test
    fun `cu ly chi dung cho DUNG package`() {
        NavViewIdSource.publish(waze, 140, "Quang Trung", "", -1, -1, now = 1_000L)
        assertEquals(140, NavViewIdSource.freshTurnMetersFor(waze, 1_500L))
        assertEquals(-1, NavViewIdSource.freshTurnMetersFor("vn.vietmap.live", 1_500L))
    }

    /** Hết tươi thì không dùng — HUD thà bỏ trống ô cự ly còn hơn hiện số cũ của đoạn đã đi qua. */
    @Test
    fun `het tuoi thi khong dung`() {
        NavViewIdSource.publish(waze, 140, "Quang Trung", "", -1, -1, now = 1_000L)
        assertEquals(140, NavViewIdSource.freshTurnMetersFor(waze, 1_000L + NavViewIdSource.FRESH_MS))
        assertEquals(-1, NavViewIdSource.freshTurnMetersFor(waze, 1_000L + NavViewIdSource.FRESH_MS + 1))
    }

    /** Không đọc được cự ly (chỉ có tên đường) → trả -1, không bịa 0. */
    @Test
    fun `khong doc duoc cu ly thi tra -1 chu khong phai 0`() {
        NavViewIdSource.publish(waze, -1, "Quang Trung", "", -1, -1, now = 1_000L)
        assertEquals(-1, NavViewIdSource.freshTurnMetersFor(waze, 1_100L))
    }

    /** Chuỗi cự ly thật của Waze phải parse đúng qua [NavParse] (đường mà service dùng). */
    @Test
    fun `chuoi cu ly that cua Waze parse dung`() {
        assertEquals(140, NavParse.parseMeters("140 m"))
        assertEquals(1200, NavParse.parseMeters("1.2 km"))
        assertEquals(750, NavParse.parseMeters("750 m"))
    }

    @Test
    fun `clear xoa sach`() {
        NavViewIdSource.publish(waze, 140, "Quang Trung", "5:50 PM", 360, 1200, now = 1_000L)
        NavViewIdSource.clear()
        assertNull(NavViewIdSource.freshReadingFor(waze, 1_100L))
        assertEquals(-1, NavViewIdSource.freshTurnMetersFor(waze, 1_100L))
    }

    // ── B-III (2026-08-22): ảnh chụp NHẤT QUÁN cho [TurnDistancePlausibility] ─────────────────────────────

    /**
     * T19 — khoá chống ĐỌC-RÁCH. Guard phán quyết theo BỘ BA (cự ly + tên đường + mốc); đọc rời từng
     * `@Volatile` thì ghép được `road` của mẫu N với `turnMeters` của mẫu N+1 ⇒ guard thấy "đổi tên đường"
     * giả và cho qua một cú nhảy tăng thật.
     */
    @Test
    fun `freshReadingFor tra composite NHAT QUAN cua cung mot lan publish`() {
        NavViewIdSource.publish(waze, 140, "Quang Trung", "5:50 PM", 360, 1200, now = 1_000L)
        val r = NavViewIdSource.freshReadingFor(waze, 1_500L)
        assertNotNull(r)
        assertEquals(waze, r!!.pkg)
        assertEquals(140, r.turnMeters)
        assertEquals("Quang Trung", r.road)
        assertEquals(1_000L, r.atMs)

        NavViewIdSource.publish(waze, 120, "Nguyen Van Linh", "5:51 PM", 300, 1000, now = 1_800L)
        val r2 = NavViewIdSource.freshReadingFor(waze, 1_900L)!!
        assertEquals(120, r2.turnMeters)
        assertEquals("Nguyen Van Linh", r2.road, "cự ly và tên đường phải cùng thuộc MỘT lần publish")
        assertEquals(1_800L, r2.atMs)
    }

    /**
     * T20 — khoá TƯƠNG ĐƯƠNG với `freshTurnMetersFor` cũ (hàm đó nay viết lại qua đây): sai pkg / quá
     * `FRESH_MS` / không có cự ly ⇒ null, và hai đường luôn đồng ý với nhau.
     */
    @Test
    fun `freshReadingFor — sai pkg, qua FRESH_MS hoac meters am deu tra null`() {
        NavViewIdSource.publish(waze, 140, "Quang Trung", "", -1, -1, now = 1_000L)
        assertNull(NavViewIdSource.freshReadingFor("vn.vietmap.live", 1_500L))
        assertNull(NavViewIdSource.freshReadingFor(waze, 1_000L + NavViewIdSource.FRESH_MS + 1))
        assertNotNull(NavViewIdSource.freshReadingFor(waze, 1_000L + NavViewIdSource.FRESH_MS))

        NavViewIdSource.publish(waze, -1, "Quang Trung", "", -1, -1, now = 2_000L)
        assertNull(NavViewIdSource.freshReadingFor(waze, 2_100L))
        assertEquals(-1, NavViewIdSource.freshTurnMetersFor(waze, 2_100L))
    }

    /** T21 — khoá thứ tự dọn: `clear()` phải xoá ảnh chụp TRƯỚC, không để consumer đọc bản trỏ vào field dở. */
    @Test
    fun `clear xoa luon anh chup composite`() {
        NavViewIdSource.publish(waze, 140, "Quang Trung", "", -1, -1, now = 1_000L)
        assertNotNull(NavViewIdSource.freshReadingFor(waze, 1_100L))
        NavViewIdSource.clear()
        assertNull(NavViewIdSource.freshReadingFor(waze, 1_100L))
        assertEquals(-1, NavViewIdSource.freshTurnMetersFor(waze, 1_100L))
    }
}
