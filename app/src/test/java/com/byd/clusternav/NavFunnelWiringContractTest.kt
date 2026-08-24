package com.byd.clusternav

import com.byd.clusternav.testsupport.SourceRoots
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **F4 bước 1** (spec `docs/specs/nav-input-output-architecture.html`) — MỘT CỬA VÀO.
 *
 * Ba nhóm khẳng định, đều là thứ unit-test hành vi KHÔNG nhìn thấy được (`NavRepository` là `object` dựng
 * `Handler(Looper.getMainLooper())` ngay lúc khởi tạo nên `:app` không có Robolectric thì không test bằng giá
 * trị được — logic thật vì thế đã nằm ở `:core` `NavContentBuilder`, ở đây chỉ khoá DÂY NỐI):
 *
 *  1. **Đường Google Maps KHÔNG bị đụng** — owner dặn: đường đã proven ngoài hiện trường thì không chỉnh
 *     (CLAUDE.md §6). Nó vẫn vào đúng `NavRepository.ingest(...)` như cũ, và `handle()` không được đan một
 *     mẩu nào của đường ảnh vào.
 *  2. **`ingest` chỉ còn là vỏ** — mọi phép tính đã dời sang `NavContentBuilder.fromNotification`; nếu ai đó
 *     tính lại một trường ngay trong `NavRepository` thì bảng vàng `GmapsContentGoldenTest` (`:core`) không
 *     canh được nữa, vì nó chỉ canh builder.
 *  3. **Đường ảnh đã nối vào cửa chính** — có call site thật cho cả hai seam (CLAUDE.md §8: hàm mới mà không
 *     ai gọi thì compile xanh vẫn là code chết; đúng ca `CastShell.evictVd`).
 */
class NavFunnelWiringContractTest {

    private val repo by lazy { SourceRoots.text("src/main/java/com/byd/clusternav/NavRepository.kt") }
    private val listener by lazy { SourceRoots.text("src/main/java/com/byd/clusternav/NavNotificationListener.kt") }
    private val bridge by lazy { SourceRoots.text("src/main/java/com/byd/clusternav/NavOutputOwner.kt") }

    /** Thân của một `fun <name>(` cho tới dấu đóng `\n    }` ở cấp 4-space. */
    private fun functionBody(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue(start >= 0, "không tìm thấy fun $name")
        val rest = text.substring(start)
        val end = rest.indexOf("\n    }")
        assertTrue(end > 0, "không tìm thấy dấu đóng của fun $name")
        return rest.substring(0, end)
    }

    // ── 1. Đường Google Maps ────────────────────────────────────────────────────────────────────────

    /** Call site DUY NHẤT của đường notification phải còn NGUYÊN VĂN, đủ 4 đối số. */
    @Test
    fun `duong notification van goi ingest y nguyen`() {
        assertTrue(
            listener.contains("NavRepository.ingest(applicationContext, sbn.packageName, null, state)"),
            "đường Google Maps phải giữ đúng call site cũ",
        )
        assertTrue(
            listener.contains("val MAPS_PACKAGES = com.byd.clusternav.navigation.NavApps.NOTIFICATION"),
            "roster kênh notification không được đổi trong việc này",
        )
    }

    /**
     * Hai đường KHÔNG được đan vào nhau. `handle()` là đường DATA; nếu nó bắt đầu đọc tín hiệu ảnh thì ta
     * quay lại đúng cái mớ mà 08-22/08-23 đã phải gỡ (mượn mũi tên từ screen-capture — B3.42).
     */
    @Test
    fun `handle() khong duoc doc mot manh nao cua duong anh`() {
        val body = functionBody(listener, "handle")
        listOf("ScreenCaptureSignal", "NavViewIdSource", "NavOutputDecision", "NavContentBuilder", "NavOutputOwner")
            .forEach { name ->
                assertFalse(body.contains(name), "handle() (đường DATA) không được biết tới $name")
            }
    }

    /** CLAUDE.md §7 — khác biệt giữa app phải lộ ra qua ĐO, không qua tên gói cứng trong listener. */
    @Test
    fun `listener khong duoc re nhanh theo ten goi`() {
        listOf("if (pkg ==", "if (packageName ==", "if (sbn.packageName ==")
            .forEach { pattern -> assertFalse(listener.contains(pattern), "cấm rẽ nhánh theo tên gói: $pattern") }
    }

    // ── 2. `ingest` chỉ còn là vỏ ───────────────────────────────────────────────────────────────────

    @Test
    fun `NavRepository ingest khong con tu tinh truong nao`() {
        val body = functionBody(repo, "ingest")
        assertTrue(
            body.contains("NavContentBuilder.fromNotification("),
            "ingest phải dựng khung qua builder dùng chung",
        )
        assertFalse(body.contains("NavParse."), "ingest không được tự parse — đã dời sang builder")
        assertFalse(body.contains("NavigationFrameContent("), "ingest không được tự dựng content")
        assertTrue(body.contains("publish(value)"), "vẫn phải đẩy CHÍNH NavState gốc cho UI (mang bitmap mũi tên)")
    }

    // ── 3. Đường ảnh đã nối vào cửa chính ───────────────────────────────────────────────────────────

    @Test
    fun `cua chinh co call site that tu duong anh`() {
        assertTrue(
            bridge.contains("NavRepository.ingestContent(appContext.applicationContext, pkg, null, content)"),
            "ctor prod của đường ảnh phải nối seam ingest xuống cửa chính",
        )
        assertTrue(
            bridge.contains("NavRepository.stopIfSource(appContext.applicationContext, pkg)"),
            "ctor prod của đường ảnh phải nối seam nhả phiên xuống cửa chính",
        )
        assertTrue(repo.contains("fun ingestContent("), "cửa chính phải phơi đường vào cho khung ảnh")
        assertTrue(repo.contains("fun stopIfSource("), "cửa chính phải phơi đường nhả phiên theo chủ")
    }

    /**
     * Bài học F1 — nhả phiên phải CÓ ĐIỀU KIỆN. Nhả vô điều kiện là giết phiên Google Maps đang chạy chỉ vì
     * một kênh ảnh của app khác hết tươi.
     */
    @Test
    fun `stopIfSource phai so chu truoc khi dung phien`() {
        val body = functionBody(repo, "stopIfSource")
        assertTrue(
            body.contains("current.identity?.packageName != packageName"),
            "phải so danh tính nguồn đang giữ phiên trước khi dừng",
        )
        assertTrue(body.contains("return false"), "không khớp chủ ⇒ no-op, không dừng gì cả")
    }

    /** [NavContentBuilder] phải được CẢ HAI đường dùng — nếu chỉ một đường dùng thì nó chưa phải "một cửa". */
    @Test
    fun `NavContentBuilder duoc dung o CA HAI duong`() {
        val users = Files.walk(SourceRoots.path("src/main/java/com/byd/clusternav")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it.toFile().readText().contains("NavContentBuilder.from") }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
        assertEquals(
            listOf("NavOutputOwner.kt", "NavRepository.kt"), users,
            "đúng hai call site: đường ảnh + đường notification",
        )
    }
}
