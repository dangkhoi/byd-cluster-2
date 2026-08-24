package com.byd.clusternav

import com.byd.clusternav.navigation.NavApps
import com.byd.clusternav.navigation.NavChannel
import com.byd.clusternav.navigation.NavSourceLabels
import com.byd.clusternav.navigation.NavSourceMode
import com.byd.clusternav.navigation.SourceArbiter
import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá **roster app dẫn đường** khỏi lệch nhau (08-22).
 *
 * ── VÌ SAO PHẢI CÓ ───────────────────────────────────────────────────────────────────────────────────────
 * Danh sách gói từng bị chép ở 5 nơi. Nguy hiểm nhất là bản **XML**
 * (`res/xml/nav_accessibility_config.xml` → `android:packageNames`): đó là cổng của `system_server`. Thiếu
 * một gói ở đó thì framework **không giao AccessibilityEvent** cho service ⇒ nguồn đó chết câm — không
 * crash, không log, không test đỏ. Kotlin không import được XML nên chỉ có test này bắc cầu được.
 *
 * Đây đúng loại lỗi CLAUDE.md §8 nói: "compile xanh không có nghĩa là code chạy".
 */
class NavPackageRosterSyncTest {

    private val xml by lazy { SourceRoots.text("src/main/res/xml/nav_accessibility_config.xml") }

    /** `android:packageNames` trong XML phải khớp CHÍNH XÁC [NavApps.ALL]. */
    @Test
    fun `XML packageNames khop chinh xac NavApps ALL`() {
        val attr = Regex("""android:packageNames="([^"]+)"""").find(xml)
        assertTrue(attr != null, "không tìm thấy android:packageNames trong nav_accessibility_config.xml")
        val fromXml = attr!!.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertEquals(
            NavApps.ALL, fromXml,
            "roster XML lệch NavApps.ALL — gói thiếu ở XML sẽ KHÔNG nhận được event a11y (chết câm)",
        )
    }

    /** Hai roster Kotlin ở `:app` phải là chính [NavApps], không phải bản chép. */
    @Test
    fun `roster Kotlin khong con ban chep`() {
        assertEquals(NavApps.NOTIFICATION, NavNotificationListener.MAPS_PACKAGES)
        val svc = SourceRoots.text("src/main/java/com/byd/clusternav/modules/navaccess/NavAccessibilityService.kt")
        assertTrue(svc.contains("navPackages = NavApps.ALL"), "navPackages phải trỏ NavApps.ALL")
        assertTrue(svc.contains("maps = NavApps.GMAPS"), "nhánh GMaps-only phải trỏ NavApps.GMAPS")
    }

    /**
     * Thay cho test cũ ở `NavSourceLabelsTest` vốn **RỖNG**: nó đi qua `SourceArbiter.shouldFeed(..., AUTO)`,
     * mà nhánh AUTO không đọc ba set package dòng nào — xoá sạch cả ba set test vẫn xanh. Ở đây assert THẲNG.
     */
    @Test
    fun `moi goi trong roster deu co nhan hang, khong roi ve ten goi tho`() {
        for (pkg in NavApps.ALL) {
            val label = NavSourceLabels.sourceLabel(pkg)
            assertNotEquals(pkg, label, "gói $pkg chưa có nhãn hãng — status line sẽ hiện tên gói thô")
            assertTrue(label.isNotBlank(), "gói $pkg cho nhãn rỗng")
        }
    }

    /**
     * Roster KHẢ NĂNG [NavApps.DESC_ONLY] (app chỉ phơi content-desc, không có resource-id) phải là **tập
     * con** của roster đọc-được [NavApps.ALL].
     *
     * VÌ SAO: một gói nằm trong DESC_ONLY mà KHÔNG có trong ALL thì nó không nằm trong `android:packageNames`
     * ⇒ `system_server` không giao AccessibilityEvent ⇒ nhánh content-desc chết câm y hệt lỗi roster lệch mà
     * cả file test này sinh ra để chặn. Và nó phải RỜI KHỎI họ Waze: họ Waze có resource-id, cho nó đi nhánh
     * content-desc là chạy thừa một vòng đi cây trên mỗi nhịp enum.
     */
    @Test
    fun `roster DESC_ONLY nam trong ALL va khong dam vao ho Waze`() {
        assertTrue(NavApps.DESC_ONLY.isNotEmpty(), "roster rỗng ⇒ nhánh content-desc là code chết")
        assertTrue(NavApps.ALL.containsAll(NavApps.DESC_ONLY), "gói ngoài ALL sẽ không nhận được event a11y")
        assertTrue(
            NavApps.DESC_ONLY.none { it in NavApps.WAZE },
            "họ Waze đọc bằng view-id — không được đưa vào nhánh content-desc",
        )
        assertTrue(
            NavApps.DESC_ONLY.none { it in NavApps.GMAPS },
            "GMaps đi đường notification đã proven — nhánh content-desc không được chạm vào (CLAUDE.md §6)",
        )
    }

    /**
     * Roster **KÊNH** [NavApps.NOTIFICATION] vs roster **ĐỌC-ĐƯỢC** [NavApps.ALL] — khoá cả hai chiều.
     *
     * ── VÌ SAO (08-23) ───────────────────────────────────────────────────────────────────────────────
     * (a) NOTIFICATION ⊆ ALL: một gói đi kênh notification mà không có trong `android:packageNames` thì mất
     *     kênh a11y — nửa dữ liệu biến mất im lặng, đúng lỗi cả file này sinh ra để chặn.
     * (b) GMaps PHẢI ở trong NOTIFICATION: đó là đường ĐANG CHẠY ngoài hiện trường (CLAUDE.md §6). Bỏ nó ra
     *     là tắt nguồn nav chính của xe.
     * (c) VietMap PHẢI ở NGOÀI NOTIFICATION nhưng Ở TRONG ALL: ngoài để notification của nó không đóng mốc
     *     `SourceArbiter.lastDataByPkg[vietmap]` (mốc đó chặn kênh IMAGE ⇒ mất mũi tên screen-capture,
     *     backlog B3.42); trong ALL để **không mất kênh content-desc** — `NavApps.DESC_ONLY` chỉ có tác dụng
     *     nếu `system_server` còn giao AccessibilityEvent cho gói đó.
     * (d) NOTIFICATION ∩ DESC_ONLY = ∅: DESC_ONLY nghĩa là "app chỉ phơi dữ liệu qua content-desc". Một gói
     *     ở cả hai là mâu thuẫn khai báo — và là đúng cấu hình đã hỏng ở B3.42.
     */
    @Test
    fun `roster kenh NOTIFICATION tach dung khoi roster doc-duoc ALL`() {
        assertTrue(NavApps.ALL.containsAll(NavApps.NOTIFICATION), "gói ngoài ALL sẽ không nhận được event a11y")
        assertTrue(NavApps.NOTIFICATION.containsAll(NavApps.GMAPS), "GMaps là đường notification đã proven — §6")
        assertEquals(NavApps.GMAPS, NavApps.NOTIFICATION, "chỉ GMaps có mũi tên trong notification (đo 08-20)")
        for (pkg in NavApps.VIETMAP) {
            assertTrue(pkg !in NavApps.NOTIFICATION, "$pkg đi kênh notification ⇒ tự khoá kênh IMAGE của chính nó")
            assertTrue(pkg in NavApps.ALL, "$pkg phải còn trong ALL, nếu không thì mất luôn kênh content-desc")
        }
        for (pkg in NavApps.WAZE) {
            assertTrue(pkg !in NavApps.NOTIFICATION, "$pkg: notification chỉ có tickerText (đo 08-22) — 0 dữ liệu")
            assertTrue(pkg in NavApps.ALL, "$pkg phải còn trong ALL (kênh view-id a11y)")
        }
        assertTrue(
            NavApps.NOTIFICATION.none { it in NavApps.DESC_ONLY },
            "một gói không thể vừa 'chỉ phơi qua content-desc' vừa cấp dữ liệu qua notification",
        )
    }

    /**
     * MẶT HÀNH VI của cùng bất biến (đo trên chính [SourceArbiter], không suy luận): với gói đi kênh
     * notification, một nhịp DATA **chặn** kênh IMAGE; với VietMap — gói KHÔNG đi kênh notification — không
     * có ai đóng mốc DATA nên kênh IMAGE **vẫn mở**. Đây chính là bất biến owner yêu cầu 08-23:
     * *"VietMap có notification thì kênh IMAGE vẫn mở"*.
     */
    @Test
    fun `VietMap co notification thi kenh IMAGE VAN MO`() {
        SourceArbiter.clear()
        val vietmap = NavApps.VIETMAP.first()
        val gmaps = NavApps.GMAPS.first()

        // Cơ chế (mặt đối chứng): gói TRONG roster notification đi qua handle() ⇒ shouldFeed(DATA) ⇒ IMAGE bị chặn.
        assertTrue(gmaps in NavApps.NOTIFICATION)
        assertTrue(SourceArbiter.shouldFeed(gmaps, NavSourceMode.AUTO, 1_000L, NavChannel.DATA))
        assertFalse(
            SourceArbiter.shouldFeed(gmaps, NavSourceMode.AUTO, 1_100L, NavChannel.IMAGE),
            "kênh DATA còn tươi thì ảnh phải thua — đây là cơ chế, không phải bug",
        )

        // VietMap: listener không bao giờ gọi shouldFeed cho nó (không ở roster kênh) ⇒ chỉ có nhịp IMAGE.
        SourceArbiter.clear()
        assertTrue(vietmap !in NavApps.NOTIFICATION)
        assertFalse(SourceArbiter.isDataFresh(vietmap, 1_000L), "không ai được đóng mốc DATA cho VietMap")
        assertTrue(
            SourceArbiter.shouldFeed(vietmap, NavSourceMode.AUTO, 1_000L, NavChannel.IMAGE),
            "mũi tên + cự ly screen-capture của VietMap phải lên được cụm",
        )
        SourceArbiter.clear()
    }

    /**
     * Tiền tố resource-id của họ Waze phủ CẢ bản zin lẫn bản mod.
     * Đo tĩnh bằng aapt2 (2026-08-22): WazeMod DUAL có manifest `com.chisadin.wazemod` nhưng
     * `resources.arsc` ghi `Package name=com.waze` ⇒ `viewIdResourceName` mang tiền tố `com.waze`.
     * Waze zin thì applicationId = arsc package = `com.waze`. Nên MỘT tiền tố là đủ, không cần hỏi người dùng.
     */
    @Test
    fun `tien to resource Waze phu ca ban zin lan ban mod`() {
        assertTrue(NavApps.WAZE_RES_PREFIX in NavApps.WAZE, "tiền tố phải chính là gói Waze zin")
        assertTrue("com.chisadin.wazemod" in NavApps.WAZE, "bản mod phải nằm trong nhóm Waze")
        assertEquals(2, NavApps.WAZE.size, "nhóm Waze = {zin, mod}")
    }
}
