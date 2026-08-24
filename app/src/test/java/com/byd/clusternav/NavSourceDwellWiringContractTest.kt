package com.byd.clusternav

import com.byd.clusternav.testsupport.KotlinSource
import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B-II — khoá phần DÂY NỐI trong `NavAccessibilityService`. Phần này chạm `AccessibilityWindowInfo` /
 * `AccessibilityNodeInfo` nên KHÔNG chạy được trên android.jar stub của JVM; ta khoá bằng QUÉT SOURCE, đúng
 * kỹ thuật của [NavForegroundGateContractTest] / [ScreenCaptureNavSourceContractTest]. Chính sách THUẦN đã
 * được `NavSourceDwellTest` (:core) phủ off-car.
 */
class NavSourceDwellWiringContractTest {

    private val access =
        SourceRoots.text("src/main/java/com/byd/clusternav/modules/navaccess/NavAccessibilityService.kt")
    private val listener = SourceRoots.text("src/main/java/com/byd/clusternav/NavNotificationListener.kt")
    private val broadcaster = SourceRoots.text("src/main/java/com/byd/clusternav/ClusterBroadcaster.kt")

    private val enumFn get() = functionBody(access, "private fun resolveNavWindowRegardlessOfFocus(now: Long)")

    /**
     * KHOÁ: mọi lần publish của đường enum phải nằm SAU quyết định dwell. Publish trước rồi mới hỏi dwell là
     * đúng cái bug đang chữa (ứng viên bị loại vẫn ghi đè holder MỘT-Ô → cự ly nhảy giữa hai app).
     */
    @Test
    fun `bau nguon di qua navDwell onTick TRUOC moi publish`() {
        val body = enumFn
        val tick = body.indexOf("navDwell.onTick(\n")
        assertTrue(tick >= 0, "vòng enum phải chấm nhịp qua navDwell.onTick(...)")
        val publishReading = body.indexOf("publishViewIdReading(elected, reading, now)")
        val publishFg = body.indexOf("CaptureForegroundSource.publish(elected, now)")
        assertTrue(publishReading > tick, "ghi holder view-id chỉ sau khi dwell đã bầu nguồn")
        assertTrue(publishFg > tick, "publish foreground chỉ sau khi dwell đã bầu nguồn")
        // Và publish chỉ dành cho nguồn ĐÃ ĐƯỢC BẦU (`elected`), không phải ứng viên vừa dò được (`winner`).
        assertTrue(
            body.contains("if (elected == winner && reading != null) publishViewIdReading(elected, reading, now)"),
            "chỉ publish khi nguồn được bầu CHÍNH LÀ ứng viên vừa đọc được",
        )
    }

    /**
     * KHOÁ: bước DÒ phải CÂM. [NavViewIdSource]/`CaptureBoundsSource` là holder MỘT-Ô — dò mà publish luôn thì
     * dwell vô nghĩa, vì ứng viên bị loại đã kịp ghi đè cự ly của nguồn đang giữ.
     */
    @Test
    fun `probeNavByViewId CAM - than ham khong chua publish holder nao`() {
        val probe = functionBody(access, "private fun probeNavByViewId(")
        assertFalse(probe.contains("NavViewIdSource.publish"), "bước dò KHÔNG được ghi NavViewIdSource")
        assertFalse(probe.contains("CaptureBoundsSource.publish"), "bước dò KHÔNG được ghi CaptureBoundsSource")
        // Việc ghi holder nằm ở hàm riêng, chỉ gọi cho nguồn đã bầu.
        val pub = functionBody(access, "private fun publishViewIdReading(")
        assertTrue(pub.contains("NavViewIdSource.publish("), "hàm publish mới giữ việc ghi holder")
        assertTrue(pub.contains("CaptureBoundsSource.publish("), "bounds mũi tên cũng chỉ ghi ở đây")
    }

    /**
     * KHOÁ (08-23) — nhánh **content-desc** (VietMap) phải chịu ĐÚNG luật của nhánh view-id (Waze):
     *
     *  1. **CÂM** — dò mà publish luôn thì ứng viên bị [com.byd.clusternav.navigation.NavSourceDwell] loại vẫn
     *     ghi đè cự ly của nguồn đang giữ (holder MỘT-Ô) → "cự ly nhảy giữa hai app". Đúng bug B-II đã chữa.
     *  2. **DÙNG LẠI vòng duyệt window đã có** — nhánh mới nằm TRONG `probeRanked`, không thêm vòng
     *     `getWindows()`/`getWindowsOnAllDisplays()` thứ hai (mỗi vòng enum là cost thật trên `system_server`,
     *     đó là lý do có `WINDOW_ENUM_THROTTLE_MS`).
     *  3. **ĐƯỜNG CŨ ĐI TRƯỚC** (CLAUDE.md §6 — đường mới xuống cuối): view-id thử trước, content-desc chỉ
     *     được hỏi khi view-id trả null ⇒ họ Waze không bao giờ chạm nhánh mới.
     */
    @Test
    fun `nhanh content-desc CAM, dung lai vong window cu, va dung SAU nhanh view-id`() {
        val ranked = functionBody(access, "private fun probeRanked(")
        assertTrue(
            ranked.contains("probeNavByViewId(rp, root) ?: probeNavByContentDesc(rp, root)"),
            "content-desc phải là nhánh SAU của view-id, trên CÙNG một root",
        )
        val desc = functionBody(access, "private fun probeNavByContentDesc(")
        assertFalse(desc.contains("NavViewIdSource.publish"), "bước dò KHÔNG được ghi NavViewIdSource")
        assertFalse(desc.contains("CaptureBoundsSource.publish"), "bước dò KHÔNG được ghi CaptureBoundsSource")
        assertFalse(desc.contains("SourceArbiter."), "bước dò KHÔNG được chạm trọng tài")
        val code = stripComments(desc)
        assertEquals(0, Regex("""getWindowsOnAllDisplays""").findAll(code).count(), "không thêm vòng enum mới")
        assertEquals(0, Regex("""\bwindows\b""").findAll(code).count(), "không tự đi lấy danh sách cửa sổ")
        // Cổng KHẢ NĂNG lấy từ roster MỘT-NƠI, không phải chuỗi tên gói rải trong code (CLAUDE.md §7).
        assertTrue(code.contains("NavApps.DESC_ONLY"), "cổng phải đọc roster NavApps.DESC_ONLY")
        assertEquals(
            0,
            Regex("""["']vn\.vietmap""").findAll(access).count(),
            "không hardcode tên gói VietMap trong service — roster nằm ở NavApps",
        )
    }

    /**
     * KHOÁ HỒI QUY [P0]+[P1] (08-22 vòng 1): vòng enum cửa sổ **CHỈ ĐƯỢC HỎI** trọng tài, TUYỆT ĐỐI KHÔNG ghi
     * gì vào nó. Nó quan sát CỬA SỔ, nó KHÔNG phải một kênh dữ liệu dẫn đường.
     *
     * Bản 08-22 gọi `SourceArbiter.shouldFeed(elected, mode, nowWall)` ở cuối vòng (channel mặc định = DATA).
     * Hai hỏng hóc tất định:
     *  • [P0] đóng mốc `lastDataByPkg[elected]` mỗi 800 ms ⇒ `isDataFresh` luôn true ⇒ cả 4 lối publish kênh
     *    ẢNH của `ScreenCaptureNavSource` (gate `NavChannel.IMAGE`) trả false ⇒ mũi tên/làn/camera KHÔNG BAO
     *    GIỜ lên cụm — toàn bộ B3 thành code chết trên xe.
     *  • [P1] đặt `activeSource = elected` mỗi 800 ms (nhanh hơn `STALE_MS` = 6 s) ⇒ ở AUTO, khung
     *    notification GMaps bị `allows()` chặn vĩnh viễn chừng nào còn một cửa sổ nav khác hiện — bỏ đói ĐÚNG
     *    đường đang chạy ngoài hiện trường (CLAUDE.md §6).
     *
     * `noteSeen` cũng bị cấm ở đây. Nó từng là `public` (bản 08-22) rồi `private`, và từ 2026-08-23 đã bị
     * XOÁ HẲN khỏi :core cùng sổ `lastSeenByPkg`/`isGroupFresh` (owner chốt: `PREFER_X` chỉ là `pkg in X` —
     * backlog B3.48, xem `SourceArbiterAllowsTest`). Vế assert dưới vì thế nay là **canh cửa**: nó phải ở
     * lại để một lần "khôi phục sổ đã thấy" trong tương lai không lặng lẽ nối lại vào vòng enum — nơi ghi sổ
     * biến "app CÓ CỬA SỔ" thành "app CÒN DẪN" và khoá chết PREFER_*.
     */
    @Test
    fun `vong enum CHI HOI trong tai - khong shouldFeed, khong noteSeen`() {
        val body = enumFn
        // Đếm trên phần CODE (đã bỏ comment): thân hàm có comment giải thích chính hai lời gọi bị cấm, mà
        // comment thì không thi hành gì cả — đếm cả comment là test tự bắt lỗi văn bản của chính nó.
        val code = stripComments(body)
        assertEquals(
            0,
            Regex(Regex.escape("SourceArbiter.shouldFeed(")).findAll(code).count(),
            "vòng enum KHÔNG được gọi shouldFeed (đóng mốc DATA + chiếm activeSource)",
        )
        assertEquals(
            0,
            Regex("""SourceArbiter\.noteSeen\(""").findAll(code).count(),
            "vòng enum KHÔNG được ghi sổ 'đã thấy'",
        )
        assertTrue(body.contains("SourceArbiter.allows(pkg, mode, nowWall)"), "ứng viên chỉ được HỎI cổng")
        assertTrue(
            body.contains("if (!SourceArbiter.allows(elected, mode, nowWall)) return"),
            "nguồn đã bầu vẫn phải qua cổng NguồN của user, nhưng bằng hàm THUẦN",
        )
    }

    /**
     * KHOÁ: bẫy hai-miền-đồng-hồ (wall-clock đầu xe NHẢY khi đồng bộ giờ GPS — xem ScreenCaptureNavSource) +
     * hằng số nhịp chỉ nằm MỘT nơi (chép ra hai chỗ là chuỗi dwell tính sai khi đổi throttle).
     */
    @Test
    fun `navDwell dung dong ho MONOTONIC va tickPeriodMs = WINDOW_ENUM_THROTTLE_MS`() {
        assertTrue(access.contains("clock = { SystemClock.elapsedRealtime() }"), "dwell dùng đồng hồ monotonic")
        assertTrue(access.contains("tickPeriodMs = WINDOW_ENUM_THROTTLE_MS"), "chu kỳ nhịp lấy từ hằng số thật")
        // Arbiter vẫn ở miền WALL — trộn hai miền là isFresh luôn 'tươi' → cổng kẹt.
        val body = enumFn
        assertTrue(body.contains("val nowWall = System.currentTimeMillis()"), "arbiter giữ miền wall-clock")
        assertTrue(body.contains("holderTurnMeters(it, now)"), "cự ly holder chấm bằng đồng hồ monotonic `now`")
    }

    /**
     * KHOÁ HỒI QUY (08-22 vòng 3): `holderAllowed` — cờ mà R3 dùng để **bỏ qua CẢ dwell LẪN guard cam-kết-rẽ**
     * — phải lấy từ cổng THUẦN MODE [com.byd.clusternav.navigation.SourceArbiter.allowedByMode], KHÔNG phải
     * `allows()`.
     *
     * VÌ SAO: KDoc `NavSourceDwell.onTick` định nghĩa tham số này là "holder còn qua cổng NavSourceMode không
     * (user đổi PREFER_* ⇒ nhường NGAY)". Nhưng ở AUTO, `allows()` KHÔNG phải cổng mode — nó là khoá-giữ theo
     * `activeSource`. Truyền `allows()` vào đây ⇒ mỗi khi một app KHÁC đang thật sự nuôi khung (`activeSource`
     * = ứng viên), R3 bắn `adopt(Reason.MODE)` TỨC THÌ, vượt mặt R5 dù holder còn 50 m tới điểm rẽ — đúng "cú
     * nhảy nguy hiểm nhất" mà R5 sinh ra để chặn, và log còn ghi nhãn sai là "user đổi chế độ".
     * Ca hỏng dựng đầy đủ ở `SourceArbiterAllowsTest` (:core, off-car).
     *
     * Cổng CUỐI vòng (quyết định có publish hay không) thì vẫn phải là `allows()` — đó mới là chỗ khoá-giữ
     * AUTO có nghĩa; test `vong enum CHI HOI trong tai` ở trên khoá vế đó.
     */
    @Test
    fun `holderAllowed lay tu cong THUAN MODE, khong phai khoa-giu AUTO`() {
        val code = stripComments(enumFn)
        assertTrue(
            code.contains("holderAllowed = hold != null && SourceArbiter.allowedByMode(hold, mode, nowWall)"),
            "R3 chỉ được nhường ngay cho lựa chọn PREFER_* của user, không cho khoá-giữ AUTO",
        )
        assertEquals(
            0,
            Regex(Regex.escape("holderAllowed = hold != null && SourceArbiter.allows(")).findAll(code).count(),
            "truyền allows() vào holderAllowed là mở lại đường R3 vượt mặt R5",
        )
    }

    /** KHOÁ: holder vẫn phải LÃO HOÁ khi không còn cửa sổ nav nào — nếu không, nguồn đã tắt bị giữ vĩnh viễn. */
    @Test
    fun `khong co ung vien nao thi van cham nhip de holder lao hoa`() {
        // Đo trên phần CODE (đã bỏ comment): cửa sổ 400 ký tự tính trên text THÔ thì một comment giải thích
        // thêm ở nhánh rỗng là đẩy lời gọi ra ngoài cửa sổ ⇒ test ĐỎ vì văn bản, không vì hành vi (đã xảy ra
        // 08-23 vòng 2). Comment không thi hành gì cả, nó không được tham gia phép đo.
        val body = stripComments(enumFn)
        val empty = body.indexOf("if (ranked.isEmpty())")
        assertTrue(empty >= 0, "vẫn có nhánh không-ứng-viên")
        val window = body.substring(empty, (empty + 400).coerceAtMost(body.length))
        assertTrue(
            window.contains("navDwell.onTick(candidate = null, holderAllowed = false)"),
            "nhánh rỗng phải chấm nhịp (R0) trước khi return",
        )
    }

    /** KHOÁ: phiên mới bắt đầu SẠCH — service (re)connect không được thừa kế holder của phiên trước. */
    @Test
    fun `onServiceConnected reset dwell`() {
        val body = functionBody(access, "override fun onServiceConnected()")
        assertTrue(body.contains("navDwell.reset()"), "phiên mới phải reset dwell")
    }

    /**
     * KHOÁ: CLAUDE.md §6 — đường notification GMaps đang chạy NGOÀI HIỆN TRƯỜNG, B-II không được chạm một dòng
     * nào của nó.
     */
    @Test
    fun `duong notification GMaps khong biet gi ve dwell`() {
        assertFalse(listener.contains("NavSourceDwell"), "NavNotificationListener không được biết tới dwell")
        assertFalse(broadcaster.contains("NavSourceDwell"), "ClusterBroadcaster không được biết tới dwell")
    }

    /**
     * KHOÁ: đường event a11y giữ NGUYÊN chuỗi cũ, nên hai contract test đang khoá nó
     * ([NavForegroundGateContractTest], [ScreenCaptureNavSourceContractTest]) vẫn xanh — B-II chỉ đổi đường ENUM.
     */
    @Test
    fun `duong event a11y giu nguyen chuoi CaptureForegroundSource publish pkg b3Now`() {
        assertTrue(access.contains("CaptureForegroundSource.publish(pkg, b3Now)"), "đường event không đổi")
        assertFalse(enumFn.contains("CaptureForegroundSource.publish(pkg, b3Now)"), "đường enum publish `elected`")
    }

    /**
     * KHOÁ (sửa 08-22 vòng 2): rect dải làn KHÔNG ĐƯỢC mang một danh tính ĐOÁN.
     *
     * `maybePublishLaneBounds` đọc `rootInActiveWindow` — cửa sổ đang FOCUS, KHÔNG nhất thiết là cửa sổ đã bắn
     * event. Bản trước rớt về `?: eventPkg` khi `root.packageName` rỗng/null, tức DỰNG một danh tính. Hậu quả
     * nằm ở tận consumer: `ScreenCaptureNavSource` chốt `NavFrameIdentity.sameFrame(pkg, lb.pkg)` rồi crop —
     * nhãn đoán mà trùng thì rect của app A được crop ra khỏi ảnh app B, cho ra pixel HỢP LỆ với nhãn SAI, và
     * KHÔNG tầng nào phát hiện được. [NavFrameIdentity] ghi rõ "rỗng/null KHÔNG bao giờ là danh tính hợp lệ ⇒
     * im lặng" — luật đó phải được thi hành ngay tại NƠI SẢN XUẤT, không chỉ ở KDoc.
     */
    @Test
    fun `lane bounds khong duoc doan chu bang eventPkg`() {
        val body = stripComments(functionBody(access, "private fun maybePublishLaneBounds("))
        assertEquals(
            0,
            Regex("""\?:\s*eventPkg""").findAll(body).count(),
            "không được rớt về eventPkg — không biết chủ thì BỎ nhịp này",
        )
        assertTrue(
            body.contains("if (ownerPkg == null)") && body.contains("return"),
            "thiếu chủ ⇒ phải return (degrade-safe), không publish",
        )
        // Và nhãn vẫn phải là package RUNTIME, tuyệt đối không phải tiền tố resource dùng chung của họ Waze
        // (`com.waze` cũng là tiền tố của WazeMod ⇒ dán nhãn đó là gộp hai app cài song song làm một).
        assertFalse(body.contains("LaneBoundsSource.publish(WAZE_RES_PREFIX"), "nhãn phải là package runtime")
        assertTrue(body.contains("LaneBoundsSource.publish(ownerPkg"), "chỉ publish với chủ đọc được từ node")
    }

    /**
     * KHOÁ (08-23 vòng 3) — MỘT ô holder chỉ được mang MỘT miền đồng hồ.
     *
     * `NavViewIdSource.Reading.arrivalClock` có HAI nơi sản xuất: `probeNavByViewId` (Waze — `lblArrivalTime`
     * phơi **12 h**: `'5:50 PM'`, chuỗi ĐÃ ĐO, xem KDoc `NavViewIdSource`) và `probeNavByContentDesc` (VietMap
     * — `VietMapDescParser.parseEta` đã chuẩn hoá **24 h**). Bản trước ghi chuỗi Waze THÔ ⇒ một ô, hai miền.
     *
     * Ba kết cục hạ nguồn, ĐO THẬT bằng probe 08-23 (không suy luận):
     *  • `NavParse.extractArrivalClock("5:50 PM")` = `"5:50"` → sai **12 TIẾNG**;
     *  • `NavigationFrame.init` require `\d{1,2}:\d{2}` → `"5:50 PM"` **NÉM** IllegalArgumentException;
     *  • `BydHal` §ETA_H `"5:50 PM".split(":")` = `[5, null]` → giờ 5 (đúng 17) + phút **rụng im lặng**.
     *
     * Hôm nay trường này chưa có consumer nên chưa hỏng trên xe — đúng lý do phải khoá NGAY: consumer đầu
     * tiên nhận một trong ba kết cục đó mà không có gì báo. Chuẩn hoá thuộc về NƠI SẢN XUẤT (CLAUDE.md §13).
     *
     * Test này ĐỎ nếu ai đó gỡ `extractArrivalClock24` khỏi producer (đã verify bằng cách gỡ thử).
     * Miền/luật của chính phép đổi 12 h→24 h được `NavParseTest` phủ off-car (:core, THUẦN).
     */
    @Test
    fun `producer view-id CHUAN HOA dong ho ve 24h truoc khi ghi holder`() {
        val probe = stripComments(functionBody(access, "private fun probeNavByViewId("))
        assertTrue(
            probe.contains("NavParse.extractArrivalClock24("),
            "giờ tới nơi của Waze (12 h) phải đổi sang 24 h ngay tại nơi sản xuất",
        )
        assertEquals(
            0,
            Regex(Regex.escape("""arrival = textOf("lblArrivalTime").orEmpty()""")).findAll(probe).count(),
            "ghi THÔ lblArrivalTime là để '5:50 PM' lọt vào holder — NavigationFrame.require sẽ ném",
        )
        // §6 — đường notification GMaps dùng `extractArrivalClock` (12 h, KHÔNG hiểu AM/PM). Nó phải giữ NGUYÊN:
        // hàm 24 h là hàm RIÊNG, chỉ cho producer mới. Đổi tại chỗ = lặng lẽ đổi hành vi một đường đã proven.
        val repo = SourceRoots.text("src/main/java/com/byd/clusternav/NavRepository.kt")
        assertTrue(
            repo.contains("arrivalClock = NavParse.extractArrivalClock(value.eta)"),
            "đường notification GMaps giữ nguyên extractArrivalClock — không được đổi sang bản 24 h",
        )
    }

    // §4.1 DRY + FAIL-OPEN: bản regex chép tay ở đây (2 bản) coi `//` trong string literal là comment ⇒
    // nuốt luôn phần thi hành đứng sau trên cùng dòng ⇒ guard mù mà vẫn xanh. Đã chuyển sang scanner
    // có trạng thái dùng chung, có test riêng (`KotlinSourceTest`).
    private fun stripComments(src: String): String = KotlinSource.stripComments(src)

    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        var depth = 0
        var opened = false
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> { depth++; opened = true }
                '}' -> if (opened && --depth == 0) return text.substring(start, i + 1)
            }
        }
        error("unterminated $signature")
    }
}
