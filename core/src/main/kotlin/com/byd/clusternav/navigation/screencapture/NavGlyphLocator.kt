package com.byd.clusternav.navigation.screencapture

import com.byd.clusternav.navigation.ArrayPixelFrame
import com.byd.clusternav.navigation.PixelFrame

/**
 * Dò **bbox mực của đảo sáng lớn-đủ, trái nhất** trong ô cửa sổ app — THUẦN pixel, không Android, không hình
 * học cố định. Với glyph một-thành-phần (đa số) đảo đó CHÍNH LÀ mũi tên; xem "GIỚI HẠN ĐÃ BIẾT" bên dưới cho
 * glyph nhiều thành phần.
 *
 * ── VÌ SAO PHẢI CÓ (đo 2026-08-22) ────────────────────────────────────────────────────────────────────────
 * Trên cụm, người dùng chỉnh được **dpi**, **kích thước**, **vị trí** cửa sổ cast và cast **một hoặc hai**
 * app (xem `CastShell`: `wm size` / `wm density` / `am task resize` / chia đôi). Mọi rect crop CỐ ĐỊNH đều
 * chết ngay khi bố cục đổi. Thêm nữa, chính Waze cũng đổi bố cục banner theo độ dài tên đường (2 dòng cao
 * 150px ↔ 1 dòng cao 93px ở dpi 240) nên kể cả khi người dùng không đụng gì thì rect cố định vẫn trượt.
 *
 * ── BỘ CORPUS ĐANG ĐO TRÊN (mọi số [ĐO] trong file này) ───────────────────────────────────────────────────
 * 87 khung **ghép** VietMap (glyph render từ asset APK dán lên một khung chụp THẬT đã xoá mũi tên —
 * `core/src/test/resources/diagnostics/vietmap-glyph/`, độ trung thực kiểm 3 lần, xem `VietMapGlyphGateTest`)
 * + 8 khung **chụp thật** Waze/WazeMod ở 4 dpi × 4 cỡ màn + 3 khung **âm** chụp thật
 * (`core/src/test/resources/diagnostics/glyph/`). Tổng **98** khung: **11** là ảnh chụp nguyên văn (8 dương
 * Waze + 3 âm), 87 còn lại là glyph asset dán lên MỘT khung chụp thật.
 * ⚠ 8 khung Waze là **dải TRÊN** cắt từ ảnh chụp (cao 240–320 px) nên `roiH` khi test chỉ bằng ~44 % lúc
 * chạy thật; `NavGlyphLocatorTest.ROI day du…` đắp chúng lên nền bản đồ thật cho đủ chiều cao màn để bù.
 *
 * ── CÁCH NHẬN RA GLYPH ───────────────────────────────────────────────────────────────────────────────────
 *  0. **PHẢI BIẾT Ô CỬA SỔ APP** — [locate] nhận [CropRect] KHÔNG cho null (xem KDoc [locate]).
 *  1. **Đảo SÁNG** — mũi tên vẽ trắng (luma > [BRIGHT]).
 *  2. **Bị bao quanh bởi vùng TỐI** — banner nav là khối tối đặc ([ringIsDark], xét TỪNG phía).
 *  3. **SÀN nhìn-được** — [MIN_DP] (theo dp) **HOẶC** [MIN_H_WIN_FRAC] (theo chiều cao cửa sổ), lấy cái LỚN
 *     hơn. Hai đường đo độc lập nhau nên một dpi khai sai không hạ được sàn (xem KDoc [MIN_H_WIN_FRAC]).
 *  4. **HÌNH DẠNG — hai tỉ số**: tỉ lệ khung [MIN_ASPECT]…[MAX_ASPECT] và tỉ lệ lấp [MIN_FILL]…[MAX_FILL].
 *  5. **NEO TRÁI** — [MAX_LEFT_ANCHOR]: mép trái đảo phải cách **mép trái CỬA SỔ APP** không quá vài lần cỡ
 *     của chính nó. Mũi tên nằm ở đầu banner; một icon giữa bản đồ thì không.
 * Có nhiều đảo thoả thì lấy đảo **TRÁI NHẤT** — mũi tên luôn đứng trước cự ly và tên đường.
 *
 * ⚠ 08-23 vòng 3 — bản trước chỉ có (1)(2)(3) + cổng lấp, và cỡ bị kẹp giữa **hai** trần dp hiệu chuẩn trên
 * mũi tên Waze. Hệ quả [ĐO]: 60/87 maneuver VietMap trả **icon POI trên bản đồ** thay vì mũi tên, và chỉ
 * 27/87 dò đúng. Ràng buộc (4)(5) thay hai trần dp bằng **tỉ số**.
 * ⚠ 08-23 vòng 3b (phản biện) — thêm (0) và vế thứ hai của (3) sau khi ĐO ra hai đường dương-tính-giả nữa;
 * cả hai đều là "cổng đúng nhưng ĐẦU VÀO của cổng sai". Xem KDoc [locate] và [MIN_H_WIN_FRAC].
 *
 * ── VÌ SAO TRẢ BBOX MỰC, KHÔNG TRẢ "KHUNG VẼ" ─────────────────────────────────────────────────────────────
 * 38 template [com.byd.clusternav.navigation.ManeuverRegistry] (mượn nguyên của OpenBYD) nằm trong quy ước
 * **khung vẽ có lề** — chúng sinh ra từ bounds view a11y `<wazePkg>:id/navBarDirection`. KHÔNG suy ngược được
 * khung đó từ bbox mực: đo cả 38 mục thì mực luôn kết thúc ở hàng 13/15 nhưng hàng BẮT ĐẦU biến thiên 1..7
 * tuỳ maneuver. Thử fit một tỉ lệ nới chung → không có; thử quét nhiều ứng viên rồi lấy khớp tốt nhất →
 * **tệ hơn**: 4/9 khung ra SAI hướng (`off_ramp_normal_left` → chếch trái) vì template sai thắng điểm.
 *
 * Nên ta dùng **chính bbox mực làm quy ước CỦA MÌNH** và khớp với registry riêng
 * ([com.byd.clusternav.navigation.WazeArrowRegistry]). Quy ước này bất biến theo dpi/size — đo trên 7 khung
 * CÙNG một maneuver ở dpi 160/200/240/320 và 4 kích thước màn: Hamming nội-lớp **0–17** (cùng dpi = 0).
 *
 * ⚠ ĐÍNH CHÍNH 08-23 vòng 2: bản cũ ở đây viết "liên-lớp 62–72, biên cách nhau ~45 bit". Con số đó đo trên
 * bộ Waze 2 LỚP (trái/phải). Registry hiện có ~16 lớp và biên liên-lớp thật là **41 bit** — vẫn trên biên
 * bắt buộc 2×18+1 = 37, nhưng dư 4 bit chứ không phải 45. Biên này được KHOÁ bằng
 * `WazeArrowRegistryTest.moi cap template KHAC khoa quyet dinh phai cach >= 37 bit`, không phải bằng lời văn
 * ở đây — thêm template mà test đỏ thì bỏ template, đừng sửa số.
 *
 * ── GIỚI HẠN ĐÃ BIẾT (nói ra để không ai đọc nhầm hợp đồng) ───────────────────────────────────────────────
 *  · **Glyph NHIỀU THÀNH PHẦN**: hàm này trả bbox của MỘT đảo, không phải hợp của mọi đảo tạo nên glyph.
 *    [ĐO] 08-23 vòng 3b trên 87 khung VietMap: 10 tên trả về một MẢNH — `arrive` `arrive_left` `arrive_right`
 *    `arrive_straight` `depart` `depart_left` `depart_right` `depart_straight` `rotary` `roundabout`
 *    (vd `rotary` trả (42,93)-(76,142) 34×49 trong khi hợp mực là (42,82)-(127,167) 85×85). Hiện VÔ HẠI:
 *    cả 10 crop đều ra `(không khớp)` ở [com.byd.clusternav.navigation.ManeuverSignature.classifyWazeInk]
 *    (Hamming nhỏ nhất tới template gần nhất = 32 > ngưỡng 18). Nhưng bất biến "mọi cặp template khác-khoá
 *    cách ≥ 37 bit" chỉ ràng buộc template↔template, KHÔNG ràng buộc crop-một-mảnh↔template ⇒ thêm template
 *    mới có thể kéo một crop-mảnh vào trong 18 bit. Canary:
 *    `VietMapGlyphGateTest.glyph nhieu thanh phan khong duoc ra ma AMAP`. Việc sửa (gộp thành phần) = B3.52.
 *  · **Chỉ chạy cho mực SÁNG trên nền TỐI.** [ĐO] 08-23 vòng 3b: đảo màu toàn khung (banner trắng, mũi tên
 *    tối) ⇒ `turn_right` trả null. App theme SÁNG không được phủ. Kết quả là im lặng (an toàn), không phải
 *    sai hướng — nhưng đừng viết ở đâu khác rằng locator "chạy cho MỌI app".
 */
object NavGlyphLocator {

    /** Ngưỡng luma coi là "mực sáng" của glyph. */
    const val BRIGHT = 200

    /** Ngưỡng luma coi là "nền tối" của banner (dùng cho vành bao quanh). */
    const val DARK = 70

    /**
     * Luma TRUNG BÌNH của ROI banner phải > mức này thì [locateAny] mới coi là **light/day theme** và thử
     * cực đảo màu. Dark theme (nền + banner tối) có mean thấp hơn nhiều ⇒ không kích hoạt đảo màu ⇒ không đẻ
     * dương-tính-giả ở ca "dark mode giữa hai khúc, không mũi tên". 128 = giữa thang 0..255.
     */
    const val LIGHT_BG_MEAN = 128

    /**
     * **SÀN nhìn-được** của chiều cao glyph, theo dp (nhân với dpi khai báo của display).
     *
     * Là một **sàn**, không phải trần: nó không loại một glyph vì glyph đó TO — tức không tái sinh lỗi B3.47
     * (xem [MAX_FILL]). Lý do giữ: không app dẫn đường nào vẽ mũi tên maneuver nhỏ hơn ~20 dp (tài xế phải
     * đọc được trong một cái liếc); dưới ngưỡng đó là **icon status bar / chữ**, không phải chỉ dẫn.
     *
     * Sàn này **gánh việc thật** — [ĐO] 08-23 vòng 3 trên fixture: gỡ nó ra thì 3 rect icon status bar
     * (9×15 và 10×15 px @dpi240, lấp 0.29–0.36, w/h 0.60–0.67, neo trái 0.93 / 2.00 / 2.80) qua sạch mọi
     * cổng tỉ số còn lại và **thắng vì nằm bên trái mũi tên** ⇒ crop sai.
     *
     * ⚠ Nhưng nó nhân với **dpi KHAI BÁO**, không phải dpi thật ⇒ một mình nó không đủ. Xem [MIN_H_WIN_FRAC].
     */
    const val MIN_DP = 20

    /**
     * **SÀN nhìn-được thứ HAI** — theo tỉ lệ với CHIỀU CAO CỬA SỔ APP. Sàn thật = `max` của hai vế.
     *
     * ⚠ ĐÂY LÀ CỔNG SINH RA ĐỂ ĐÓNG MỘT ĐƯỜNG DƯƠNG-TÍNH-GIẢ CÓ THẬT ([P1] 08-23 vòng 3b).
     * [MIN_DP] quy ra px bằng `dpi/160`, mà `dpi` đến từ regex `(\d{2,4})dpi` trên output `am stack list`
     * ([CaptureLocationResolver]) — nếu con số đó **thấp hơn dpi vẽ thật** thì sàn tụt theo. [ĐO] trên khung
     * `turn_right` (vẽ ở dpi 240) khai dpi khác nhau, CHỈ có [MIN_DP]:
     * ```
     * dpi khai <= 124  ->  (14,11,23,26) 9x15  = ICON STATUS BAR, cho CẢ 87/87 maneuver
     * dpi khai >= 128  ->  (40,85,135,172)     = mũi tên
     * ```
     * Tức toàn bộ độ an toàn nằm trên một con số parse-được-từ-shell. Vế này đo bằng đại lượng KHÔNG đi qua
     * dpi: chiều cao ô cửa sổ (px) của chính khung đang chụp.
     *
     * Chọn 0.025 từ số đo, hai đầu:
     *  · mũi tên THẬT / chiều cao cửa sổ THẬT — nhỏ nhất là Waze `w1920h1080-d240` (mũi tên cao 53 px, cửa
     *    sổ 1080) = **0.049** ⇒ dư địa **1.96×**; các ca khác 0.05–0.089;
     *  · icon status bar 15 px / 1080 = **0.0139** ⇒ dư địa **1.80×** phía rác.
     * Là **tỉ số hai đại lượng px cùng khung** ⇒ bất biến với `wm density`, `wm size`, chia đôi màn (test
     * `VietMapGlyphGateTest.bat bien ti le` phóng ×2 cả khung lẫn dpi vẫn ra bbox ×2).
     *
     * Đánh đổi đã biết: một app vẽ mũi tên nhỏ hơn 2.5 % chiều cao cửa sổ sẽ bị bỏ ⇒ **im lặng**, không phải
     * sai hướng. Theo CLAUDE.md (im lặng > vẽ sai) đây là chiều hỏng chấp nhận được.
     */
    const val MIN_H_WIN_FRAC = 0.025f

    /** Bề dày vành kiểm-tối quanh glyph, theo dp. */
    const val RING_DP = 6

    /**
     * Tỉ lệ LẤP của đảo (số điểm sáng / diện tích bbox) — **tỉ số ⇒ bất biến tỉ lệ**.
     *
     * ⚠ 08-23 vòng 3 — ĐÃ GỠ `MAX_DP` (trần chiều cao 58 dp) VÀ NỚI `MAX_FILL` 0.35 → 0.60.
     * Lý do (B3.47, `docs/diagnostics/vietmap-glyph-gate-measurement-2026-08-23.md`): trần 58 dp được hiệu
     * chuẩn trên mũi tên **Waze** (33–42 dp) nên nó là một giả định về *app nào đang vẽ*, không phải về hình
     * dạng mũi tên. Mũi tên VietMap cao 64 dp ⇒ `turn_right` lọt với **biên 0 px** còn cả họ
     * `straight`/`slight`/`roundabout` (95–96 px @dpi240) rớt thẳng. Owner chốt 08-23: *"vì nó có thể chỉnh
     * DPI nên phải có giải pháp cho việc này, không hardcode được đâu"* ⇒ nới 58→65 bị BÁC (chỉ dời chỗ chết
     * sang app thứ ba). Thay bằng bộ ba **TỈ SỐ** dưới đây; trần kích thước không cần nữa vì flood-fill vốn
     * bị nhốt trong ROI, và một mảng sáng chiếm gần hết ROI luôn rớt ở [MAX_ASPECT] hoặc [MAX_FILL].
     *
     * Số [ĐO] 08-23 vòng 3b, chạy **chính locator này** trên toàn corpus:
     *   · lấp của 94 glyph ĐƯỢC CHỌN: **0.2065 – 0.4968** (Waze 0.2065–0.2467 · VietMap 0.2452–0.4968, cao
     *     nhất ở `rotary_sharp_right`/`roundabout_sharp_right`) ⇒ trần 0.60 để lại **21 %** dư địa.
     *
     * ⚠ Đã CÂN NHẮC RỒI BÁC bằng số đo: nâng [MIN_FILL] 0.10 → ~0.17 để tự tay loại họ icon POI trên bản đồ
     * (lấp 0.106–0.144) mà không cần nhờ [MAX_LEFT_ANCHOR]. BÁC vì đo đủ họ POI trong khung nền thì lấp của
     * nó chạy tới **0.1676** (ô 42×26), trong khi glyph thật thấp nhất là **0.2065** — hai họ chỉ cách nhau
     * **1.23×** và mỗi mép chỉ do 1–2 mẫu định ra. Siết ở đó là lặp đúng cái sai của `MAX_DP`: một cổng hiệu
     * chuẩn trên vài mẫu, chết ở app thứ ba. Họ POI được đóng bằng [MAX_LEFT_ANCHOR] (dư địa 7.7×) và bằng
     * điều kiện "ô cửa sổ phải đúng" ở [locate].
     */
    const val MIN_FILL = 0.10f
    const val MAX_FILL = 0.60f

    /**
     * Tỉ lệ khung `bề rộng / chiều cao` của đảo — **tỉ số ⇒ bất biến tỉ lệ**, thay cho cặp trần px cũ
     * (`gw <= MAX_DP*1.6`).
     *
     * [ĐO] 08-23 vòng 3b trên cùng bộ corpus: 94 glyph được chọn nằm trong **0.417 – 1.171** (Waze
     * 0.833–0.970 · VietMap 0.417–1.171); đảo rác trải **0.19 – 7.69**. Cổng [0.35, 1.45] chừa ~16 % dư địa
     * dưới và ~19 % trên.
     */
    const val MIN_ASPECT = 0.35f
    const val MAX_ASPECT = 1.45f

    /**
     * **NEO TRÁI** — khoảng cách từ mép trái CỬA SỔ APP tới mép trái đảo, đo bằng **chính cỡ của đảo**
     * (`max(gw, gh)`). Tỉ số hai đại lượng cùng thang ⇒ bất biến với dpi, với `wm size`, với chia đôi màn.
     *
     * ⚠ ĐÂY LÀ CỔNG SINH RA ĐỂ ĐÓNG MỘT ĐƯỜNG DƯƠNG-TÍNH-GIẢ CÓ THẬT ([P1] 08-23 vòng 3, B3.47).
     * Trước bản này, khi mũi tên thật bị loại thì luật "đảo TRÁI NHẤT thắng" vẫn trả về một đảo khác — [ĐO]
     * 60/87 khung VietMap trả **icon POI điểm dừng xe buýt trên BẢN ĐỒ** ở rect (603,78)-(642,117): nó qua
     * sạch cả bốn ràng buộc cũ (đảo sáng ✓ vành tối 4 phía ✓ 39×39 px ∈ [30,87] ✓ lấp 0.106 ∈ [0.10,0.35] ✓)
     * và nằm cách mũi tên **560 px** về bên phải. Crop SAI đó đi thẳng vào `classifyWazeInk`.
     *
     * Số [ĐO] 08-23 vòng 3b để chọn ngưỡng 2.0, đo trên khung nền VietMap (đã xoá mũi tên) với ô cửa sổ ĐÚNG:
     *   · 94 glyph ĐƯỢC CHỌN: **0.417 – 1.358** (ca căng nhất là **Waze** `w1920h720-d240` = 1.358, không
     *     phải VietMap — nên test biên phải duyệt CẢ hai bộ);
     *   · rác qua được sàn + hai cổng tỉ số + vành tối, bị chặn CHỈ bởi cổng này: chữ cự ly **2.75** và
     *     **3.56**, ba cụm chữ/biển trong banner 8.42 · 10.59 · 12.00, POI xe buýt **15.46**; dải LÀN của
     *     fixture `neg-lanestrip` **3.16**.
     * Ngưỡng 2.0 ⇒ dư địa **47 %** phía glyph (1.358) và **38 %** phía rác gần nhất (2.75).
     *
     * ⚠ ĐÍNH CHÍNH 08-23 vòng 3b — bản trước ghi "rác gần nhất 2.75" mà quên rằng **icon status bar ở neo
     * 2.00** (lọt với biên ĐÚNG BẰNG 0, vì cổng là `>` nghiêm ngặt) chỉ bị chặn bởi SÀN. Tức hai cổng này
     * **không độc lập** như bảng dư địa cũ trình bày. Đó là lý do vòng 3b thêm [MIN_H_WIN_FRAC]: sàn phải
     * đứng vững kể cả khi dpi khai sai, vì neo trái không đỡ được họ icon status bar.
     *
     * ⚠ TỬ SỐ LÀ `mnx - win.left` ⇒ cổng này **chỉ đúng khi `win` là ô cửa sổ THẬT của app đang chụp**. Đó
     * là lý do [locate] không nhận null. [ĐO] trượt `win.left` 0→1820 trên khung nền KHÔNG có mũi tên:
     * **102/183** vị trí trả về non-null (tại `win.left=600` trả về đúng cái POI (603,78)-(642,117)).
     */
    const val MAX_LEFT_ANCHOR = 2.0f

    /** Chỉ quét phần này của cửa sổ app (banner nav luôn ở góc trên-trái). */
    private const val ROI_W_FRAC = 0.40f
    private const val ROI_H_FRAC = 0.45f

    /**
     * Trần ROI theo **dp** — lấy min với trần theo tỉ lệ ở trên.
     *
     * Hai lý do: (a) **bộ nhớ** — mỗi nhịp (2–4 Hz) cấp HAI mảng theo diện tích ROI: `seen` BooleanArray
     * (1 B/px) + `stack` IntArray (4 B/px) = 5 B/px. Không có trần dp thì 0.40×0.45 của 1920×1080 = 768×486
     * ⇒ **1.86 MB/nhịp**; có trần thì 768×330 ⇒ **1.27 MB/nhịp** (−32 %). (b) **ít dương tính giả hơn** —
     * quét càng rộng càng dễ vồ phải thứ khác. Banner nav đo thật chỉ rộng ~430dp, cao ~80dp (2 dòng) nên
     * trần này vẫn thừa chỗ.
     *
     * ⚠ ĐÍNH CHÍNH 08-23 vòng 3b: bản trước ghi "kéo xuống còn ~1/3" và bỏ quên mảng `stack` — con số cũ
     * thấp hơn thực tế ~2×.
     *
     * ⚠ Đây là hằng theo dp nên nó cũng co lại khi dpi khai thấp; [ĐO] dpi khai 124 ⇒ `roiH` 170 < đáy mũi
     * tên (172) ⇒ mũi tên bị xén ⇒ trượt [ringIsDark]. Hướng hỏng là **quét ít đi** ⇒ im lặng, an toàn.
     */
    private const val ROI_MAX_W_DP = 520
    private const val ROI_MAX_H_DP = 220

    /**
     * Trần công (số điểm ảnh) cho MỘT lần flood-fill — chặn nổ CPU khi khung lỗi cho ra vùng sáng khổng lồ.
     * ROI lớn nhất thực tế (768×330 @dpi240) = 253 440 px nên trần này CÓ thể chạm.
     *
     * Chạm trần ⇒ **BỎ đảo đó**, không chấm. Trước 08-23 vòng 3b vòng lặp thoát với `mnx/mxx/mny/mxy` dở
     * dang rồi vẫn đi qua đủ các cổng — tức `gw`,`gh`,`fill` của một bbox chưa hoàn chỉnh vẫn có thể thắng.
     */
    private const val MAX_BLOB_PX = 200_000

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    /**
     * Trả bbox mực của glyph mũi tên trong [frame], hoặc null nếu không đảo nào thoả mọi ràng buộc.
     *
     * ⚠ [window] KHÔNG CHO NULL — và đó là một quyết định an toàn, không phải tiện tay ([P1] 08-23 vòng 3b).
     * Bản trước cho null với nghĩa "cả khung". Hai hệ quả ĐO ĐƯỢC của cái nghĩa đó:
     *  1. **Publish mũi tên của APP KHÁC dưới tên app dẫn.** Dựng khung 1920×1080 chia đôi từ hai fixture
     *     THẬT — nửa trái Waze (rẽ TRÁI), nửa phải VietMap (rẽ PHẢI) — rồi hỏi về VietMap:
     *     ```
     *     window = nửa phải (960,0,1920,1080) -> (1000,85,1095,172) -> maneuver_turn_normal_right amap=3 ✓
     *     window = null (resolver không parse) -> (  72,56, 118, 109) -> maneuver_turn_normal_left  amap=2 ✗
     *     ```
     *     Hamming 0 ⇒ khớp CHẮC CHẮN ⇒ `publishArrow(pkg=VietMap, amap=2)`: cụm vẽ RẼ TRÁI trong khi VietMap
     *     đang bảo RẼ PHẢI. Đây là đúng hạng lỗi mà §R-BI đã chặn ở đường bounds tier-1 và đường làn
     *     (`NavFrameIdentity.sameFrame`) — đường glyph là đường DUY NHẤT tự đi tìm rect nên nó phải tự chặn.
     *  2. **[MAX_LEFT_ANCHOR] mất neo.** Tử số là `mnx - win.left`; null ⇒ neo vào mép KHUNG chứ không phải
     *     mép cửa sổ ⇒ mọi số dư địa trong KDoc hằng số đó vô nghĩa.
     * Đường vào có thật, không phải giả thuyết: [CaptureLocationResolver] trả `windowRect = null` mỗi khi
     * không parse ra task, và KDoc của chính nó ghi ca đó **đã xảy ra thật** trên API 34 (regex chỉ-`Stack`
     * không khớp `RootTask id=`), lỗi im lặng. Không biết cửa sổ ở đâu ⇒ **im lặng**, không đoán.
     *
     * @param frame khung ĐÃ chụp (toạ độ của [window] phải cùng không gian với khung này).
     * @param window ô app đang chiếm ([AppLocation.windowRect]) — caller không có thì ĐỪNG gọi.
     * @param densityDpi dpi KHAI BÁO của display (0 → [DisplayGeometry.DENSITY_DEFAULT]); sai số của nó đã
     *   được [MIN_H_WIN_FRAC] chặn ở chiều "khai thấp".
     */
    fun locate(frame: PixelFrame, window: CropRect, densityDpi: Int): CropRect? {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return null
        val px = frame.argb() ?: return null
        if (px.size < w * h) return null

        val win = window.clampTo(CropRect(0, 0, w, h))
        if (win.width <= 0 || win.height <= 0) return null

        val dpi = if (densityDpi > 0) densityDpi else DisplayGeometry.DENSITY_DEFAULT
        val scale = dpi / 160f

        val roiW = minOf((win.width * ROI_W_FRAC).toInt(), (ROI_MAX_W_DP * scale).toInt()).coerceAtLeast(1)
        val roiH = minOf((win.height * ROI_H_FRAC).toInt(), (ROI_MAX_H_DP * scale).toInt()).coerceAtLeast(1)
        val rx1 = minOf(win.right, win.left + roiW)
        val ry1 = minOf(win.bottom, win.top + roiH)
        if (rx1 <= win.left || ry1 <= win.top) return null

        // SÀN = max(theo dp, theo chiều cao cửa sổ). Hai đường đo độc lập ⇒ dpi khai sai không hạ được sàn.
        val lo = maxOf((MIN_DP * scale).toInt(), (win.height * MIN_H_WIN_FRAC).toInt())
        val ring = (RING_DP * scale).toInt().coerceAtLeast(3)

        fun luma(i: Int): Int {
            val c = px[i]
            return (((c ushr 16) and 0xFF) + ((c ushr 8) and 0xFF) + (c and 0xFF)) / 3
        }

        val scanW = rx1 - win.left
        val scanH = ry1 - win.top
        val seen = BooleanArray(scanW * scanH)
        val stack = IntArray(scanW * scanH)        // ngăn xếp toạ độ phẳng (tránh đệ quy sâu)
        var best: CropRect? = null

        for (sy in win.top until ry1) {
            for (sx in win.left until rx1) {
                val sIdx = (sy - win.top) * scanW + (sx - win.left)
                if (seen[sIdx] || luma(sy * w + sx) <= BRIGHT) continue
                // ── flood fill 4-liên-thông trong ROI (ngăn xếp phẳng, không đệ quy) ──
                var top = 0
                stack[top++] = sIdx
                seen[sIdx] = true
                var mnx = sx; var mxx = sx; var mny = sy; var mxy = sy; var n = 0
                while (top > 0 && n < MAX_BLOB_PX) {
                    val cur = stack[--top]
                    val cy = win.top + cur / scanW
                    val cx = win.left + cur % scanW
                    n++
                    if (cx < mnx) mnx = cx
                    if (cx > mxx) mxx = cx
                    if (cy < mny) mny = cy
                    if (cy > mxy) mxy = cy
                    var k = 0
                    while (k < 4) {
                        val nx = cx + DX[k]
                        val ny = cy + DY[k]
                        k++
                        if (nx < win.left || nx >= rx1 || ny < win.top || ny >= ry1) continue
                        val idx = (ny - win.top) * scanW + (nx - win.left)
                        if (seen[idx]) continue
                        if (luma(ny * w + nx) <= BRIGHT) continue
                        seen[idx] = true
                        stack[top++] = idx
                    }
                }
                // (0) Chạm trần công ⇒ bbox DỞ DANG, không được đem đi chấm (xem [MAX_BLOB_PX]).
                if (top > 0) continue
                val gw = mxx + 1 - mnx
                val gh = mxy + 1 - mny
                // (1) SÀN nhìn-được — max(dp, tỉ lệ chiều cao cửa sổ); xem [MIN_DP] + [MIN_H_WIN_FRAC].
                if (gh < lo || gw < lo / 2) continue
                // (2) TỈ LỆ KHUNG — loại vệt/dải dài và cột hẹp (xem [MIN_ASPECT]/[MAX_ASPECT]).
                val aspect = gw.toFloat() / gh
                if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue
                // (3) TỈ LỆ LẤP — loại chữ/icon đặc (xem [MIN_FILL]/[MAX_FILL]).
                val fill = n.toFloat() / (gw * gh)
                if (fill < MIN_FILL || fill > MAX_FILL) continue
                // (4) NEO TRÁI — mũi tên nằm ở mép trái banner; đo bằng chính cỡ đảo nên bất biến tỉ lệ.
                //     Đây là cổng chặn đường "mũi tên rớt ⇒ vồ một icon bản đồ" (xem [MAX_LEFT_ANCHOR]).
                if (mnx - win.left > MAX_LEFT_ANCHOR * maxOf(gw, gh)) continue
                if (!ringIsDark(mnx, mny, mxx + 1, mxy + 1, ring, w, h, px)) continue
                // đảo TRÁI NHẤT thắng (mũi tên đứng trước cự ly + tên đường)
                if (best == null || mnx < best.left) best = CropRect(mnx, mny, mxx + 1, mxy + 1)
            }
        }
        return best
    }

    /** Kết quả [locateAny]: bbox glyph + có phải phải ĐẢO MÀU khung mới tìm ra (app ở light/day theme). */
    data class Located(val rect: CropRect, val inverted: Boolean)

    /**
     * Như [locate] nhưng dò **CẢ HAI CỰC** để phủ light mode LẪN dark mode (owner 2026-08-24).
     *
     *  1. **Dark/night theme** (mực SÁNG trên nền TỐI) — gọi [locate] thẳng. Ra ⇒ `Located(rect, inverted=false)`.
     *  2. **Light/day theme** (mũi tên TỐI trên nền SÁNG) — [locate] cực 1 trả null ⇒ [PixelFrameOps.invert]
     *     khung rồi [locate] lại. Ra ⇒ `Located(rect, inverted=true)`; caller PHẢI đảo màu crop trước khi khớp
     *     registry (template là quy ước sáng-trên-tối). bbox ở CÙNG toạ độ (đảo màu không dời pixel).
     *
     * [ĐO 2026-08-24] nếu KHÔNG có cực 2: đảo màu ảnh VietMap/Waze dark thật (đang classify được) ⇒ [locate]
     * trả **null** ⇒ đó chính là "Waze never appears / VietMap dark" khi xe chạy ban ngày. Dark mode: cực 1 ra
     * ngay nên KHÔNG tốn phép đảo (chi phí 0 cho đường đang chạy tốt); chỉ light mode mới trả giá một lần đảo.
     */
    fun locateAny(frame: PixelFrame, window: CropRect, densityDpi: Int): Located? {
        // ⚡ PERF + SNAPSHOT (F4c review 2026-08-25): [locate] cực 1, [isLightBanner] và [PixelFrameOps.invert]
        // đều gọi `frame.argb()`. Với khung nền Bitmap trên xe ([BitmapPixelFrame]) MỖI `argb()` là một
        // `getPixels` TOÀN MÀN (JNI copy + cấp IntArray ~8 MB @1080p). Light/day theme là ca THƯỜNG ban ngày ⇒
        // để nguyên thì mỗi nhịp (2 Hz) tốn 3 lần getPixels toàn màn. Vật hoá điểm ảnh MỘT lần rồi bọc
        // [ArrayPixelFrame] để các bước sau đọc thẳng mảng (argb() chỉ trả mảng, không copy). Dark mode: cực 1
        // ra ngay nên vẫn đúng 1 getPixels như trước (0 phụ phí cho đường đang chạy tốt). KHÔNG đổi hành vi —
        // cùng điểm ảnh; và còn ĐÚNG HƠN: cả ba bước nay thấy CÙNG một ảnh chụp (bỏ TOCTOU giữa 3 getPixels).
        val px = frame.argb() ?: return null
        val flat: PixelFrame = if (frame is ArrayPixelFrame) frame else ArrayPixelFrame(frame.width, frame.height, px)
        locate(flat, window, densityDpi)?.let { return Located(it, false) }
        // ⚠ CHẶN DƯƠNG-TÍNH-GIẢ: cực 1 trả null ở HAI ca — (a) dark mode GIỮA hai khúc (không mũi tên), (b)
        // light mode (mũi tên tối/nền sáng). Chỉ ca (b) mới được thử đảo màu; nếu không, ở ca (a) đảo màu một
        // khung tối-không-mũi-tên sẽ biến nhiễu bản đồ thành "đảo sáng" giả → có thể khớp nhầm template → SAI
        // hướng (vi phạm im-lặng>sai-hướng). Cổng: nền ROI banner phải THỰC SỰ sáng mới đảo.
        if (!isLightBanner(flat, window, densityDpi)) return null
        val inv = PixelFrameOps.invert(flat) ?: return null
        locate(inv, window, densityDpi)?.let { return Located(it, true) }
        return null
    }

    /**
     * ROI trên-trái (nơi banner nav nằm) có nền SÁNG (light/day theme) không — luma TRUNG BÌNH > [LIGHT_BG_MEAN].
     * Dark theme (nền + banner tối) ⇒ mean thấp ⇒ false ⇒ [locateAny] KHÔNG đảo màu (không đẻ dương-tính-giả).
     * Một pass, không sort (rẻ); chỉ chạy khi cực 1 đã trả null.
     */
    private fun isLightBanner(frame: PixelFrame, window: CropRect, densityDpi: Int): Boolean {
        val w = frame.width; val h = frame.height
        val px = frame.argb() ?: return false
        if (px.size < w * h) return false
        val win = window.clampTo(CropRect(0, 0, w, h))
        if (win.width <= 0 || win.height <= 0) return false
        val dpi = if (densityDpi > 0) densityDpi else DisplayGeometry.DENSITY_DEFAULT
        val scale = dpi / 160f
        val roiW = minOf((win.width * ROI_W_FRAC).toInt(), (ROI_MAX_W_DP * scale).toInt()).coerceAtLeast(1)
        val roiH = minOf((win.height * ROI_H_FRAC).toInt(), (ROI_MAX_H_DP * scale).toInt()).coerceAtLeast(1)
        val rx1 = minOf(win.right, win.left + roiW)
        val ry1 = minOf(win.bottom, win.top + roiH)
        if (rx1 <= win.left || ry1 <= win.top) return false
        var sum = 0L; var n = 0
        var y = win.top
        while (y < ry1) {
            val base = y * w
            var x = win.left
            while (x < rx1) {
                val c = px[base + x]
                sum += (((c ushr 16) and 0xFF) + ((c ushr 8) and 0xFF) + (c and 0xFF)) / 3
                n++; x++
            }
            y++
        }
        return n > 0 && sum / n > LIGHT_BG_MEAN
    }

    /**
     * CẢ BỐN phía quanh bbox có tối không (trung vị mỗi phía < [DARK])?
     *
     * ⚠ Phải xét TỪNG PHÍA, không lấy trung vị chung: đo 08-22 thấy cụm icon status bar nằm ngay TRÊN banner
     * đen bị nhận nhầm là glyph — trái/phải nó sáng trưng (trung vị 139–144) nhưng khối banner đen phía dưới
     * quá lớn nên kéo trung vị CHUNG xuống dưới ngưỡng. Xét riêng từng phía thì loại được ngay.
     *
     * Phía nào bị tràn ra ngoài khung (không đủ điểm để lấy trung vị) ⇒ coi như KHÔNG đạt: glyph thật luôn
     * nằm lọt trong banner nên bốn phía đều phải có nền tối thật sự.
     *
     * ⚠ Cổng này SÁT: [ĐO] 08-23 vòng 3b hai glyph có bbox GIỐNG NHAU TỪNG PIXEL (63,76)-(114,172) 51×96 mà
     * một cái qua một cái rớt — `continue_straight` (lấp 0.3503) qua, `fork_straight` (lấp 0.3523) rớt. Nên
     * `fork_straight` = null KHÔNG phải hành vi thiết kế, nó là **biên của [DARK]/[RING_DP]**; ai chỉnh hai
     * hằng đó thì đọc `VietMapGlyphGateTest` trước, đừng chữa test bằng cách đổi danh sách.
     */
    private fun ringIsDark(
        x0: Int, y0: Int, x1: Int, y1: Int, ring: Int, w: Int, h: Int, px: IntArray,
    ): Boolean =
        sideDark(x0, y0 - ring, x1, y0, w, h, px) &&     // trên
            sideDark(x0, y1, x1, y1 + ring, w, h, px) && // dưới
            sideDark(x0 - ring, y0, x0, y1, w, h, px) && // trái
            sideDark(x1, y0, x1 + ring, y1, w, h, px)    // phải

    /** Trung vị luma của một dải có < [DARK] không? Dải rỗng (tràn ngoài khung) ⇒ false. */
    private fun sideDark(ax0: Int, ay0: Int, ax1: Int, ay1: Int, w: Int, h: Int, px: IntArray): Boolean {
        val bx0 = ax0.coerceAtLeast(0); val by0 = ay0.coerceAtLeast(0)
        val bx1 = ax1.coerceAtMost(w); val by1 = ay1.coerceAtMost(h)
        if (bx1 <= bx0 || by1 <= by0) return false
        val vals = IntArray((bx1 - bx0) * (by1 - by0))
        var k = 0
        for (y in by0 until by1) {
            val base = y * w
            for (x in bx0 until bx1) {
                val c = px[base + x]
                vals[k++] = (((c ushr 16) and 0xFF) + ((c ushr 8) and 0xFF) + (c and 0xFF)) / 3
            }
        }
        vals.sort()
        return vals[k / 2] < DARK
    }
}
