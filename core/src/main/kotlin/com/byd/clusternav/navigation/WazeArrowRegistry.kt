package com.byd.clusternav.navigation

/**
 * Registry NẠP-ĐƯỢC của chữ ký glyph mũi tên Waze / VietMap (chuỗi 225-bit '0/1' → tên maneuver), được
 * [ManeuverSignature] khớp SONG SONG với registry dựng-sẵn 38 mục GMaps/AMAP ([ManeuverRegistry]) — B3.6, spec
 * `docs/specs/waze-vietmap-screen-capture.html` §4.5.
 *
 * VÌ SAO (B3.6): [ManeuverRegistry].RAW tự sinh từ icon GMaps/Mapbox; glyph mũi-tên-trắng-trên-nền-đen của Waze
 * KHÔNG khớp (Hamming > 18, NCC < 0.45) nên `classify*` trả null DÙ crop đúng. Registry này cho phép thêm
 * template Waze/VietMap THẬT — sinh sau này từ ảnh PNG crop trên emulator qua [ManeuverSignature.signatureBits]
 * (đúng chuỗi 225-bit như RAW) — để `classify*` cũng nhận được mũi tên Waze/VietMap.
 *
 * [BUILTIN] = [WAZE_INK] (4 mục, thu từ ảnh chụp thật trên emulator 08-22/23) + [VIETMAP_INK] (34 mục, sinh
 * từ asset SVG trong APK VietMap Live 3.3.4 — xem KDoc của nó). ⚠ KHÔNG bịa số giả: mọi chuỗi ở đây đều
 * tái lập được từ một nguồn ghi rõ. Tên maneuver DÙNG CÙNG từ vựng với [ManeuverRegistry] (vd
 * `"maneuver_turn_normal_left"`) để [ManeuverSignature] ánh xạ TÊN→AMAP/HAL/Maneuver y hệt — không cần bảng
 * map riêng.
 *
 * ⚠ BẤT BIẾN AN TOÀN (khoá bằng `WazeArrowRegistryTest`):
 *   (a) hai template cách nhau ≤ 18 bit (`ManeuverSignature` MAX_HAMMING) PHẢI cho CÙNG bộ ba quyết định
 *       (AMAP, HAL, Maneuver) — thà thiếu một maneuver còn hơn hiện sai hướng/sai độ gấp;
 *   (b) hai template cho quyết định KHÁC nhau phải cách ≥ 2×18+1 = **37 bit** (đo được: nhỏ nhất 41). (a) là
 *       CẦN nhưng KHÔNG ĐỦ — nội-lớp đo tới 17 bit, nên nếu chỉ có (a) thì một khung lệch 11 bit khỏi
 *       template ĐÚNG vẫn có thể cách template SAI 10 bit và thắng. Thêm 08-23 vòng 2 ([P1]); biên thật
 *       trước khi thêm là **21 bit**;
 *   (c) mọi template phải cách MỌI mục [ManeuverRegistry] ít nhất 37 bit (giữ từ trước — dù từ 08-23 vòng 2
 *       `ManeuverSignature.match`/`matchNCC` đã thôi quét registry này, nên đường notification GMaps
 *       (CLAUDE.md §6) được cách ly bằng CẤU TRÚC chứ không chỉ bằng khoảng cách).
 *
 * ⚠ SỐ MỤC ≠ PHỦ SÓNG: đo bằng chính locator trên 87 khung VietMap **ghép** (glyph asset APK dán lên khung
 * chụp thật) thì **39/87** khung đi trọn
 * đường tới mã AMAP — xem mục "PHỦ SÓNG THẬT" ở KDoc [VIETMAP_INK] trước khi hứa với ai là app đọc được
 * rẽ gấp / quay đầu / tới đích.
 *
 * Thread-safe: ghi (register/load/clear) `@Synchronized` + bump [version]; đọc [raw] trả snapshot bất biến
 * (list copy-on-write) để matcher đọc không khoá. [version] để [ManeuverSignature] cache dạng-đóng-gói và chỉ
 * đóng-gói-lại khi registry đổi (production rỗng ⇒ 0 chi phí).
 */
object WazeArrowRegistry {

    /** Số bit mỗi chữ ký = lưới 15×15 (khớp [ManeuverRegistry] / [ManeuverSignature]). */
    const val BITS = 225

    /**
     * WAZE — template thu từ ẢNH CHỤP THẬT theo **quy ước BBOX MỰC** (2026-08-22).
     *
     * QUY ƯỚC: chữ ký tính trên **đúng bbox mực của glyph** do [com.byd.clusternav.navigation.screencapture
     * .NavGlyphLocator] dò ra — KHÔNG lề, KHÔNG khung vẽ. Đây là quy ước RIÊNG của đường screen-capture, khác
     * hẳn quy ước "khung vẽ có lề" của 38 mục [ManeuverRegistry] (vốn sinh từ bounds view a11y
     * `navBarDirection` của OpenBYD). **Không được trộn hai quy ước**: đo 08-22 thấy khớp chéo cho ra
     * `off_ramp_normal_left` (→ chếch trái) thay vì `turn_normal_left` (→ rẽ trái) trên 4/9 khung.
     *
     * VÌ SAO QUY ƯỚC NÀY DÙNG ĐƯỢC: nó bất biến với dpi/kích-thước. Đo 7 khung CÙNG một maneuver ở dpi
     * 160/200/240/320 và 4 kích thước màn (1280×480 → 1920×1080): Hamming nội-lớp **0–17** (cùng dpi = 0),
     * liên-lớp (trái vs phải) **62–72**. Ngưỡng khớp 18 nằm gọn giữa hai biên.
     *
     * ⚠ Con số liên-lớp 62–72 chỉ đúng cho BỘ WAZE 2 LỚP này. Từ 08-23 [BUILTIN] còn có [VIETMAP_INK] với
     * ~16 lớp và biên liên-lớp của cả bộ là **41 bit** (đo 08-23 vòng 2) — vẫn trên biên bắt buộc 37, nhưng
     * đừng trích 62–72 như thể nó là biên của registry. Xem bất biến (b) ở KDoc object.
     *
     * ⚠ WAZE MỚI CÓ 2 MANEUVER (trái/phải). Thu thêm bằng cách bật [com.byd.clusternav.NavLog].verbose rồi
     * đọc dòng `arrow-sig` — chữ ký in ra ĐÃ theo đúng quy ước này, dán thẳng vào đây kèm nhãn. Hướng nào
     * chưa thu ⇒ chưa có template ⇒ `classifyWazeInk` trả "(không khớp)" ⇒ im lặng (degrade-safe).
     *
     * ⚠ MỖI ỨNG DỤNG VẼ MŨI TÊN RIÊNG ⇒ PHẢI CÓ TEMPLATE RIÊNG, KHÔNG dùng chung. Đo 08-23: chữ ký rẽ-phải
     * của VietMap cách template rẽ-phải Waze ở đây **30 / 35 bit** — cùng HỌ (rẽ trái cách 72 / 78) nhưng
     * vượt xa ngưỡng khớp 18 ⇒ đó là lý do [VIETMAP_INK] tồn tại. KHÔNG được "chữa" bằng cách nới
     * [ManeuverSignature] MAX_HAMMING: nới tới 35 là mở đường cho trái/phải lẫn nhau (liên-lớp chỉ 62–72).
     */
    val WAZE_INK: List<Pair<String, String>> = listOf(
        // rẽ TRÁI — banner 1 dòng, d240, cụm 1920×720, ink 46×53
        "000100000000000001100000000000011111110000000111111111110000001100000011100000100000001110000000000000110000000000000011000000000000011000000000000011000000000000011000000000000011000000000000011000000000000011000000000000001" to "maneuver_turn_normal_left",
        // rẽ TRÁI — cùng maneuver ở d160 (glyph chỉ 30×36; giữ để phủ dải dpi thấp, nơi nét mảnh rụng bớt)
        "000000000000000000100000000000011111100000000011111111100000001100000011000000100000000100000000000000110000000000000011000000000000001000000000000001000000000000001000000000000001000000000000001000000000000001000000000000001" to "maneuver_turn_normal_left",
        // rẽ PHẢI — banner 2 dòng, d240, màn chính 1920×1080, ink 64×66
        "000000000001000000000000001100000000000111110000001111111111000111110011110001110000001100001100000001000011000000000000011000000000000110000000000000110000000000000110000000000000110000000000000110000000000000110000000000000" to "maneuver_turn_normal_right",
        // rẽ PHẢI — cùng maneuver trên cụm 1920×720, ink 64×75 (biến thể nét)
        "000000000001000000000000001100000000111111111000011111111111000111000001100001100000001000011000000000000111000000000000110000000000000110000000000000110000000000000110000000000000110000000000000110000000000000110000000000000" to "maneuver_turn_normal_right",
    )

    /**
     * ── VIETMAP LIVE 3.3.4 — 34 template SINH TỪ ASSET APK (2026-08-23) ────────────────────────────────
     *
     * NGUỒN (tái lập được, không phải số bịa): `vn.vietmap.live` **3.3.4** (xapk APKPure) →
     * thư mục `assets/flutter_assets/lib/assets/maps/directions_white/` — 93 file SVG mũi tên TRẮNG mà chính
     * banner dẫn đường của app vẽ ra. Công thức đầy đủ ở `vm_recipe.txt` (phiên 08-23): chèn nền đen →
     * `qlmanage -t -s 288` → cắt về bbox vùng đen (ảnh QuickLook có viền trắng) → mực = luma > 200 → bbox
     * mực → [ManeuverSignature.signatureBits]. 93 file cho **46 chữ ký PHÂN BIỆT** (nhiều tên icon dùng
     * chung một glyph, vd `turn_right` = `continue_right` = `on_ramp_right`).
     *
     * ⚠ KHÔNG dùng thư mục `assets/.../hud/directions/` — đó là bộ cho chế độ HUD riêng của VietMap, glyph khác
     * hẳn (Hamming 65–72 so với bộ banner) ⇒ nạp nhầm là mũi tên sai.
     *
     * ĐỘ TIN CẬY (mức "đã chứng minh", CLAUDE.md §2): template `turn_right` sinh từ APK so với **glyph THẬT**
     * chụp trên banner VietMap đang dẫn (emulator 08-23, ink 95×87) → **Hamming = 1**, dù template APK có ink
     * 238×220. Đó là bằng chứng quy ước bbox-mực bất biến tỉ lệ đúng như thiết kế ⇒ template sinh offline từ
     * SVG dùng được cho glyph runtime.
     *
     * ⚠ **BỘ NÀY GẮN VỚI PHIÊN BẢN APP.** Đây là chữ ký hình ảnh của asset trong VietMap Live 3.3.4. VietMap
     * đổi/redraw icon ở bản sau ⇒ chữ ký lệch ⇒ phải THU LẠI theo `vm_recipe.txt` (chạy lại bước 1–6 trên apk
     * mới), KHÔNG được vá tay từng bit.
     *
     * ── ÁNH XẠ TÊN: từ vựng Mapbox của VietMap → từ vựng [ManeuverRegistry] ────────────────────────────
     * Tên ở đây PHẢI là từ vựng [ManeuverRegistry] để [ManeuverSignature.nameToAmap]/`nameToHal`/
     * `nameToManeuver` ăn đúng — không có bảng map riêng cho VietMap (KDoc object này, DRY).
     *   · `turn_*` / `continue_*` / `new_name_*` / `notification_*` / `on_ramp_*` (cùng glyph) → `turn_*`
     *   · `end_of_road_{l,r}` + `off_ramp_{l,r}` (CHUNG glyph, VietMap không phân biệt) → `turn_normal_*`.
     *     Chọn `turn_normal` chứ không `off_ramp`: hai icon dùng chung một nét vẽ ~90°, mà `off_ramp` sẽ ra
     *     AMAP 4/5 (chếch) — hiện "chếch" cho một cú rẽ 90° ở ngã ba chữ T là **nói nhẹ đi độ gấp**, nguy
     *     hiểm hơn chiều ngược lại. Rẽ ở ngã ba cũng phổ biến hơn nhánh ra cao tốc.
     *   · **ĐÍNH CHÍNH NHÃN `fork_*` (08-23 vòng 2, [P1])**: `fork_left`/`fork_right` → `turn_normal_*`, KHÔNG
     *     phải `fork_*` (AMAP 4/5 = chếch). Nhãn cũ sai và đây là **cùng một lỗi** vừa được tránh cho
     *     `end_of_road`/`off_ramp` ngay ở gạch đầu dòng trên — bỏ sót đúng một họ. Bằng chứng đọc thẳng từ
     *     chuỗi bit trong file này (in lưới 15×15 ra mà so, không cần APK): `fork_left` có khuỷu 90° + thanh
     *     NGANG ở hàng 4–6 (`#############..` / `..############.`) y hệt `turn_left` (`#############..` /
     *     `.#############.`), chỉ khác một nhánh phụ nhỏ ở hàng 6–8; còn glyph CHẾCH thật
     *     (`continue_slight_left`) là bậc thang CHÉO (`.###.#####.....` / `.##....#####...`) với đầu mũi tên ở
     *     góc trên-trái. Hệ quả kép của nhãn cũ: vừa nói nhẹ độ gấp, vừa đẻ ra 4/12 cặp gần nhất trong bảng
     *     va chạm (22/22/25/25 bit) — đổi nhãn xoá sạch cả bốn.
     *   · `merge_*` → `maneuver_merge` (→ AMAP 9 = ĐI THẲNG). Đây là quyết định SẴN CÓ của repo, xem
     *     `ManeuverSignature.nameToAmap` nhánh `merge` ("sửa bug owner: merge hiện rẽ phải"). Bốn glyph merge
     *     (trái/phải/chếch trái/chếch phải) cùng ra `maneuver_merge` ⇒ cùng khoá quyết định, không xung đột.
     *   · `arrive*` → `destination*`.
     *   · `rotary_*` = `roundabout_*` (chung glyph) → `roundabout_enter_and_exit_ccw_*`; `exit_rotary` →
     *     `roundabout_exit_ccw`. CCW vì VN đi bên phải (vòng xuyến ngược chiều kim đồng hồ).
     *   · `uturn`/`continue_uturn` → `maneuver_u_turn_left`: đọc thẳng path SVG `uturn.svg` — thân đứng ở
     *     BÊN PHẢI (x≈202–233), đầu mũi tên ở BÊN TRÁI chỉ xuống (x≈23–157, y≈251) ⇒ đi lên rồi vòng sang
     *     TRÁI = quay đầu trái (đúng RHT của VN).
     *
     * ── ĐÃ BỎ 12 chữ ký (46 → 34) ──────────────────────────────────────────────────────────────────────
     * KHÔNG PHẢI CHỈ DẪN RẼ (3): `close` (dấu X), `flag` (cờ đích/điểm dừng), `updown`. Và 2 icon MINH HOẠ
     * ĐƯỜNG chứ không phải hướng: `road_icon_left`, `road_icon_right`.
     * BỎ VÌ THỪA, KHÔNG PHẢI VÌ "không phải chỉ dẫn" (2 — đính chính 08-23 vòng 2): `invalid` là mũi tên
     *   ĐỨNG THẲNG, cách `maneuver_straight` đúng **1 bit**; `invalid_left` cách `turn_normal_left` **1 bit**.
     *   Bỏ chúng vô hại vì glyph vẫn khớp đúng qua anh em sinh đôi — nhưng lý do là TRÙNG, không phải
     *   "không mang hướng". (Lý do cũ ghi sai; giữ nguyên quyết định, sửa lời giải thích.)
     *   (Các tên `invalid_*` KHÁC vẫn còn mặt ở đây là vì chúng **dùng chung glyph** với một maneuver thật —
     *    vd `invalid_right` = `turn_right` — nên nhãn lấy theo maneuver thật, không theo tên `invalid`.)
     * TRÙNG / KHÔNG PHÂN BIỆT ĐƯỢC (1): `fork` trơn — glyph chữ Y **không mang hướng nào**; degrade-safe
     *   (CLAUDE.md): không chắc thì IM LẶNG, không đoán.
     * ĐỤNG KHOÁ QUYẾT ĐỊNH (1): `depart`/`depart_straight` — cách `maneuver_straight` chỉ **10 và 11 bit**
     *   (dưới ngưỡng 18) trong khi `nameToHal` cho hai mã khác nhau (depart = 12, straight = 11). AMAP thì
     *   giống nhau (đều 9) nên không có rủi ro sai hướng, nhưng giữ lại cũng vô nghĩa: bỏ nó đi thì glyph
     *   depart vẫn khớp `maneuver_straight` ở 10 bit ⇒ **hành vi y hệt**, mà registry bớt một mục nhập nhằng.
     *   Chọn giữ `straight` vì nó xuất hiện suốt hành trình, còn `depart` chỉ 1 lần lúc xuất phát.
     * VI PHẠM BIÊN LIÊN-KHOÁ 37 BIT (3, bỏ 08-23 vòng 2 — [P1]; xem mục VA CHẠM dưới):
     *   · `depart_left`, `depart_right` — cách họ `turn_normal_*` chỉ **25 bit** mà khoá quyết định khác
     *     (AMAP 9 đi-thẳng vs 2/3 rẽ). Bỏ chứ không đổi nhãn thành `turn_normal_*`: glyph có vẽ khuỷu rẽ
     *     thật, nhưng "depart = ĐI THẲNG ra đường" là quyết định SẴN CÓ của repo cho từ vựng GMaps
     *     (`ManeuverSignature.nameToAmap` nhánh depart) và đổi nó là một khẳng định ngữ nghĩa MỚI mà chưa có
     *     phép đo trên xe nào chống lưng (CLAUDE.md §14). Xuất phát xảy ra 1 lần/chuyến ⇒ im lặng là rẻ.
     *   · `rotary_right`/`roundabout_right` — cách `fork_right` **21 bit**, `end_of_road_right` 27, `turn_right`
     *     34, template Waze rẽ-phải 33; tất cả đều khác khoá (11 vòng-xuyến vs 3 rẽ-phải). KHÔNG cứu được
     *     bằng đổi nhãn: cái vòng xuyến — thứ DUY NHẤT phân biệt nó với một cú rẽ phải — nằm trong nét XÁM
     *     (trắng ~40% opacity ⇒ luma ~102) mà ngưỡng mực `> 200` của `vm_recipe.txt` vứt đi (xem mục GIỚI HẠN
     *     CẤU TRÚC dưới). Giữ nó = đổi đồng xu giữa "rẽ phải" và "vòng xuyến" trên cụm xe đang chạy.
     *     ⚠ Hệ quả: **vòng xuyến ra bên PHẢI hiện im lặng** (rơi xuống đường rect cố định). Bất đối xứng với
     *     `rotary_left` (còn giữ, cách mọi khoá khác ≥ 41 bit) vì glyph trái có nhiều mực phân biệt hơn.
     *
     * ── VA CHẠM (đo lại 08-23 vòng 2, ngưỡng [ManeuverSignature] MAX_HAMMING = 18) ──────────────────────
     * Khoá quyết định = bộ ba (AMAP, HAL, Maneuver) suy từ tên. HAI bất biến, cả hai khoá bằng
     * `WazeArrowRegistryTest`:
     *
     *  (1) **mọi cặp trong 18 bit phải CÙNG khoá quyết định** — 7 cặp, tất cả cùng NHÃN:
     *       1 bit  continue/…/merge_straight ↔ fork_straight/…/turn_straight   (đều `maneuver_straight`)
     *       2 bit  fork_slight_right ↔ off_ramp_slight_right                   (đều `turn_slight_right`)
     *       2 bit  turn_sharp_left/… ↔ on_ramp_sharp_left                      (đều `turn_sharp_left`)
     *      11 bit  turn_left/… ↔ end_of_road_left/off_ramp_left                (đều `turn_normal_left`)
     *      11 bit  turn_right/… ↔ end_of_road_right/off_ramp_right             (đều `turn_normal_right`)
     *      14 bit  Waze trái d240 ↔ Waze trái d160          (biến thể dpi của CÙNG maneuver, có từ 08-22)
     *      17 bit  Waze phải 1920×1080 ↔ Waze phải 1920×720 (biến thể kích-thước, có từ 08-22)
     *
     *  (2) **mọi cặp KHÁC khoá quyết định phải cách ≥ 2×18+1 = 37 bit** — nhỏ nhất đo được **41 bit**
     *      (`turn_slight_left` ↔ `fork_left`, và đối xứng bên phải). VÌ SAO CẦN (1) LÀ KHÔNG ĐỦ: (1) chỉ nói
     *      "không có hai template khác khoá nằm sát nhau", nhưng nội-lớp đo được TỚI 17 bit — hai phân phối
     *      gần chạm nhau, nên một khung lệch 11 bit khỏi template ĐÚNG vẫn có thể chỉ cách một template SAI
     *      10 bit ⇒ template sai thắng (`matchIn` là nearest-wins, KHÔNG có guard nhập nhằng). Biên 37 là
     *      đúng lập luận bất đẳng thức tam giác mà repo ĐÃ áp cho registry GMaps ở
     *      `template muc KHONG BAO GIO lot nguong Hamming cua mot khung GMaps` — trước 08-23 vòng 2 nó không
     *      được áp cho NỘI BỘ registry mực, và biên thật khi đó chỉ **21 bit**.
     *      Test ĐỎ ⇒ **BỎ một trong hai template**, KHÔNG nới con số.
     *
     *  · Với 38 mục GMaps [ManeuverRegistry]: khoảng cách NHỎ NHẤT = **39 bit** (`merge_slight_left` ↔
     *    `maneuver_roundabout_enter_and_exit_cw_slight_left`) ⇒ trên biên 37. Từ 08-23 vòng 2 con số này
     *    không còn là hàng phòng thủ DUY NHẤT: `ManeuverSignature.match`/`matchNCC` đã thôi quét registry
     *    mực hẳn, nên đường notification GMaps (CLAUDE.md §6) không thể bị chạm dù biên có tụt.
     *
     * ── ĐÃ THAY (không để hai bản) ────────────────────────────────────────────────────────────────────
     * Template rẽ-phải VietMap **thu tay** trên emulator 08-22/23 (ink 95×87) cách template `turn_right`
     * sinh từ APK đúng **1 bit** ⇒ đã GỠ, giữ bản APK (cùng lò với các mục còn lại, tái lập được từ
     * `vm_recipe.txt`). Chuỗi thu tay vẫn còn trong test làm khung thử: nó phải phân loại ra RẼ PHẢI qua
     * template APK — đó chính là phép kiểm "template offline khớp glyph runtime".
     *
     * ── PHỦ SÓNG THẬT: 39/87 KHUNG MANEUVER ĐI TRỌN ĐƯỜNG TỚI MÃ AMAP ─────────────────────────────────
     * Nạp được vào registry KHÔNG có nghĩa là dùng được. `classifyWazeInk` có ĐÚNG MỘT call site
     * (`ScreenCaptureNavSource.handleArrowByGlyph`) và crop LUÔN đến từ
     * [com.byd.clusternav.navigation.screencapture.NavGlyphLocator], nên template nào mà glyph nguồn của nó
     * không qua nổi cổng của locator thì **vĩnh viễn không bao giờ được chấm**.
     *
     * ⚠ ĐÍNH CHÍNH 08-23 vòng 3 — bản trước ở đây ghi *"17 trên 34 mục bị `MAX_FILL` chặn"*. **SAI cả số lẫn
     * thủ phạm**: phép đo đó chạy trên *proxy lưới 15×15* của chuỗi chữ ký, mà lưới đó đã CHUẨN HOÁ kích
     * thước nên về nguyên tắc không thể nhìn thấy cổng chiều cao. Thủ phạm thật là `MAX_DP` (58 dp = 87 px
     * @dpi240, hiệu chuẩn trên mũi tên Waze 33–42 dp trong khi mũi tên VietMap cao 64 dp).
     *
     * Số dưới đây đo bằng **chính locator Kotlin** trên 87 khung 1920×1080 **ghép** từ asset APK (glyph dán
     * lên một khung chụp THẬT đã xoá mũi tên — không phải 87 ảnh chụp), khoá bằng
     * `VietMapGlyphGateTest` (`:core`) — xem `docs/diagnostics/vietmap-glyph-gate-measurement-2026-08-23.md`:
     *   · locator dò ĐÚNG mũi tên: **86/87** (trước vòng 3: **27/87**, và 60/87 trả về icon POI bản đồ);
     *   · **39/87** khung đi trọn đường tới mã AMAP. Phần hụt KHÔNG còn do cổng locator mà do chính registry
     *     này chỉ có 34 template cho ~16 lớp quyết định, cộng giới hạn "ngưỡng mực" ngay dưới đây (nhiều tên
     *     asset khác nhau vẽ CÙNG một đường mực trắng ⇒ cố tình không thêm template để giữ biên 37 bit).
     *   · **10 tên nhiều thành phần** lệch quy ước: locator lấy bbox của MỘT đảo, template sinh từ bbox
     *     TOÀN BỘ mực ⇒ `arrive` `arrive_left` `arrive_right` `arrive_straight` `depart` `depart_left`
     *     `depart_right` `depart_straight` `rotary` `roundabout` ([ĐO] 08-23 vòng 3b — bản trước ghi "4 mục",
     *     thiếu cả họ `depart` và `arrive_straight`). Cả 10 hiện ra `(không khớp)`, Hamming gần nhất **32**;
     *     canary `VietMapGlyphGateTest.glyph nhieu thanh phan chua duoc phep ra ma AMAP` giữ nguyên trạng đó.
     * ⇒ Còn hụt các họ: **tới đích, một phần rẽ-gấp/quay-đầu** — nay vì THIẾU TEMPLATE, không vì cổng chặn.
     *
     * ── GIỚI HẠN CẤU TRÚC CỦA NGƯỠNG MỰC (không phải bug — nhưng phải biết trước khi thêm template) ─────
     * `vm_recipe.txt` lấy mực = luma > 200, tức **vứt bỏ nét ngữ cảnh**: các path phụ trong SVG VietMap là
     * trắng ở opacity ~40% ⇒ render ra luma **102** (đo trực tiếp: `rotary_right` có 6207 px ở luma 102 so
     * với 17534 px trắng). Nét đó lại chính là thứ DUY NHẤT phân biệt vài họ:
     *   · `turn_left` / `end_of_road_left` / `off_ramp_left` / `fork_left` — đường mực trắng gần như trùng nhau
     *     (đó là lý do cả bốn cùng ra `turn_normal_left`, và cũng là lý do nhãn `fork_*` cũ sai);
     *   · `fork_right` / `rotary_right` — cả cái vòng xuyến nằm trong nét xám ⇒ 21 bit, không tách được.
     * Muốn tách thì phải hạ ngưỡng mực xuống ~90 và **sinh lại TOÀN BỘ** bộ template (đồng thời chỉnh
     * [com.byd.clusternav.navigation.screencapture.NavGlyphLocator.BRIGHT] cho khớp quy ước) — không được vá
     * lẻ một mục, hai lò sinh khác ngưỡng là hai quy ước khác nhau.
     */
    val VIETMAP_INK: List<Pair<String, String>> = listOf(
        // continue_left/new_name_left/notification_left/on_ramp_left/turn_left
        "000010000000000000110000000000001110000000000011111111111000111111111111100011111111111110001110000000111000110000000011000000000000011000000000000011000000000000011000000000000011000000000000011000000000000011000000000000011" to "maneuver_turn_normal_left",
        // end_of_road_left/off_ramp_left
        "000001000000000000011000000000001111000000000011111111110000111111111111100011111111111110000111000000111000011000000011000001000000011000000000000011000000000000011000000000000011000000000000011000000000000011000000000000011" to "maneuver_turn_normal_left",
        // continue_right/invalid_right/new_name_right/notification_right/on_ramp_right/turn_right
        "000000000010000000000000011000000000000011100000011111111110001111111111111011111111111110111000000011100110000000011000110000000000000110000000000000110000000000000110000000000000110000000000000110000000000000110000000000000" to "maneuver_turn_normal_right",
        // end_of_road_right/off_ramp_right
        "000000000100000000000000110000000000000111100000011111111110001111111111111011111111111110111000000111000111000000110000110000000100000110000000000000110000000000000110000000000000110000000000000110000000000000110000000000000" to "maneuver_turn_normal_right",
        // continue_slight_left/invalid_slight_left/new_name_slight_left/notification_slight_left/on_ramp_slight_left/turn_slight_left
        "111111111000000111111110000000011111100000000011111110000000011101111100000011000011111000000000000111100000000000001110000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111" to "maneuver_turn_slight_left",
        // fork_slight_left/off_ramp_slight_left
        "111111111110000111111111100000011111111000000011111111100000011110111111000011100001111110000000000011111000000000001111000000000001111000000000001111000000000001111000000000001111000000000001111000000000001111000000000001111" to "maneuver_turn_slight_left",
        // continue_slight_right/invalid_slight_right/new_name_slight_right/notification_slight_right/on_ramp_slight_right/turn_slight_right
        "000000111111111000000011111111000000001111111000000011111110000001111101110000111110000110001111000000000011100000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000" to "maneuver_turn_slight_right",
        // fork_slight_right
        "000011111111111000001111111111000000011111110000000111111110000111111011110011111100001110111110000000000111100000000000111100000000000111100000000000111100000000000111100000000000111100000000000111100000000000111100000000000" to "maneuver_turn_slight_right",
        // off_ramp_slight_right
        "000011111111111000001111111111000000111111110000001111111110000111111011110011111100001110111110000000000111100000000000111100000000000111100000000000111100000000000111100000000000111100000000000111100000000000111100000000000" to "maneuver_turn_slight_right",
        // fork_left — RẼ TRÁI 90°, KHÔNG phải chếch (xem mục "ĐÍNH CHÍNH NHÃN fork_*" ở KDoc trên)
        "000000100000000000001100000000000111100000000011111111100000111111111111100001111111111110000111100001110000001100000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111" to "maneuver_turn_normal_left",
        // fork_right — RẼ PHẢI 90°, KHÔNG phải chếch
        "000000001000000000000001110000000000001111000000001111111110000111111111111011111111111100011100001111000111000001100000111000001000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000" to "maneuver_turn_normal_right",
        // new_name_sharp_left/notification_sharp_left/turn_sharp_left
        "000000000001110000000000011111000000001111111001000011110111011101111100111011111110000111011111100000111011111000000111111111100000111111111110000111000000000000111000000000000111000000000000111000000000000111000000000000011" to "maneuver_turn_sharp_left",
        // on_ramp_sharp_left
        "000000000001110000000000011111000000001111111001000011110111011100111100111011111110000111011111100000111011111000000111111111100000111111111110000111000000000000111000000000000111000000000000111000000000000111000000000000111" to "maneuver_turn_sharp_left",
        // new_name_sharp_right/notificaiton_sharp_right/notification_sharp_right/on_ramp_sharp_right/turn_sharp_right
        "011100000000000111110000000000111111100000000111011110000100111001111100110111000011111110111000001111110111000000111110111000001111111111000011111111111000000000000111000000000000111000000000000111000000000000111000000000000" to "maneuver_turn_sharp_right",
        // continue_uturn/invalid_uturn/uturn
        "000000011110000000000111111100000001111011110000011100000111000011000000111000011000000011000011000000011000011000000011000011000000011111111111000011011111111000011001111110000011000111100000011000011000000011000010000000011" to "maneuver_u_turn_left",
        // continue/continue_straight/merge_straight
        "000000010000000000001111100000000011111110000001111111111100011111111111110000000111100000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000" to "maneuver_straight",
        // fork_straight/invalid_straight/new_name_straight/notification_straight/on_ramp_straight/turn_straight
        "000000011000000000001111100000000011111110000001111111111100011111111111110000000111100000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000000000111000000" to "maneuver_straight",
        // merge_left
        "000010000000000000111000000000001111000000000001111100000000011111110000000111111111000000000111000000000000111000000000000111000000000000111000000000000111000000000000011000000000000011100000000000001111111111000000111111111" to "maneuver_merge",
        // merge_right
        "000000000010000000000000111000000000000111100000000001111100000000011111110000000111111111000000000111000000000000111000000000000111000000000000111000000000000111000000000000110000000000001110000111111111100000111111111000000" to "maneuver_merge",
        // merge_slight_left
        "000001000000000000011110000000000111111000000001111111100000011111111110000000011100000000000011100000000000011100000000000001100000000000001110000000000000111000000000000011110000000000001111100000000000011111000000000000110" to "maneuver_merge",
        // merge_slight_right
        "000000000100000000000001110000000000111111000000001111111100000011111111110000000001110000000000001110000000000001110000000000001100000000000011100000000000111000000000011110000000001111100000000111110000000000011000000000000" to "maneuver_merge",
        // arrive
        "000001111100000000001111100000000001111100000000000000000000000000000000000000000111000000000011111110000000111111111000011111111111110011111111111110000000111000000000000111000000000000111000000000000111000000000000111000000" to "maneuver_destination",
        // arrive_straight
        "000011111110000001100000001100011000111000110010001111100010011000000000110001100000001100000110000011000000001000100000000000011000000000000000000000000111111111000000111100111100001111111111100011111111111110111111000111111" to "maneuver_destination",
        // arrive_left
        "001111000000000011000100000000010000010000000100111010000000100111001000000100110000000000100000010000000010000010000000001000100101100001001001111100000110001101110000000001111110000000011100110000000011111111000000011100111" to "maneuver_destination_left",
        // arrive_right
        "000000000111100000000001000110000000010000010000000010111001000000000111001000000000011001000000010000001000000010000010001101101000100001111100100100011101100011000011111100000000011101110000000111111110000000111001110000000" to "maneuver_destination_right",
        // rotary/roundabout
        "000000000011100000000000111100000001000111100000010000011110000100000010010000100000000001001100000000001111110000000000011110000000000001100000000000000000000011110000000000001110000000110111110000000011100100000000000000100" to "maneuver_roundabout_enter_ccw",
        // exit_rotary
        "000000110000000000001111000000000111111110000001111111111000111110111111110111000111001110000000111000000000000111000000000000111000000000000011110000000000001111000000000000011110000000000001110000000000000111000000000000111" to "maneuver_roundabout_exit_ccw",
        // rotary_left/roundabout_left
        "000000000111100000011001111110000110011100110011110011000011111111111000011011111110000011001110000000011000110000001110000000000011110000000000011000000000000011000000000000011000000000000011000000000000011000000000000011000" to "maneuver_roundabout_enter_and_exit_ccw_normal_left",
        // rotary_slight_left/roundabout_slight_left
        "111000000000000111111000000000111111110000000111111000000000111111000000000100011111111100000001110011110000000000000111000000000000111000000000000111000000000011110000000001111100000000001110000000000001100000000000001100000" to "maneuver_roundabout_enter_and_exit_ccw_slight_left",
        // rotary_slight_right/roundabout_slight_right
        "000000000001110000000111111110001111111111111000001111111111000001111111111000011111000011000111111000000000000111100000000000011110000000000011110000000001111100000111111110000000111110000000000111100000000000111100000000000" to "maneuver_roundabout_enter_and_exit_ccw_slight_right",
        // rotary_sharp_left/roundabout_sharp_left
        "000000001111000000000111111110000001110001110000001100000111000001100000011000001100000011010011100000111111111000011110111110000111100111111000110000111111100110000111100000110000100000000110000000000000110000000000000110000" to "maneuver_roundabout_enter_and_exit_ccw_sharp_left",
        // rotary_sharp_right/roundabout_sharp_right
        "000001100000100000011110001100000111111001100011111111111100111111011111110111100001111110111000011111110111000111111110111001111111111111000011111111111000000000111111000000000000111000000000000111000000000000111000000000000" to "maneuver_roundabout_enter_and_exit_ccw_sharp_right",
        // rotary_straight/roundabout_straight
        "000000110000000000011111100000000111111110000011111111111000111111111011100000001111000000000001111111000000000001111110000000000001111000000000001111000000000111110000001111111100000001111000000000001111000000000001111000000" to "maneuver_roundabout_enter_and_exit_ccw_straight",
        // rotary_uturn/roundabout_uturn
        "000011111110000001111111111100011110000011110111100000001111111100000000111111100000001111011111101111110000111111111000000000111100000000000111000000001111111111100000111111111000000011111110000000000111100000000000010000000" to "maneuver_roundabout_enter_and_exit_ccw_u_turn",
    )

    /** Toàn bộ template quy ước bbox-mực: 4 Waze + 34 VietMap. Thứ tự không ảnh hưởng kết quả khớp. */
    val BUILTIN: List<Pair<String, String>> = WAZE_INK + VIETMAP_INK

    @Volatile private var entries: List<Pair<String, String>> = BUILTIN

    /** Tăng mỗi lần registry đổi — [ManeuverSignature] dùng để biết khi nào đóng-gói-lại cache. */
    @Volatile var version: Int = 0
        private set

    /** Snapshot bất biến (chuỗi bit, tên) cho matcher. */
    fun raw(): List<Pair<String, String>> = entries

    /** Số template hiện có (chẩn đoán / test). */
    fun size(): Int = entries.size

    /**
     * Thêm MỘT (chuỗi 225-bit, tên). Bỏ qua nếu bits không đúng 225 ký tự 0/1 hoặc tên rỗng (degrade-safe:
     * template hỏng không làm gãy matcher). Idempotent theo nội dung: cặp trùng y hệt không thêm lần hai.
     */
    @Synchronized
    fun register(bits: String, name: String) {
        if (!isValid(bits) || name.isBlank()) return
        val pair = bits to name
        if (pair in entries) return
        entries = entries + pair
        version++
    }

    /**
     * Nạp nguyên bộ (thay thế tập hiện tại) — bỏ các mục không hợp lệ. Dùng khi orchestrator có sẵn bảng
     * template thu từ emulator/xe.
     */
    @Synchronized
    fun load(templates: List<Pair<String, String>>) {
        entries = templates.filter { isValid(it.first) && it.second.isNotBlank() }
        version++
    }

    /**
     * Xoá về [BUILTIN] — KHÔNG phải về rỗng: từ 08-22/23 [BUILTIN] đã có template THẬT (4 Waze + 34 VietMap).
     * Chủ yếu cho test để không rò template tổng hợp sang test khác.
     */
    @Synchronized
    fun clear() {
        entries = BUILTIN
        version++
    }

    private fun isValid(bits: String): Boolean =
        bits.length == BITS && bits.all { it == '0' || it == '1' }
}
