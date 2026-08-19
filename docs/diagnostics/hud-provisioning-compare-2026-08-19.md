# HUD provisioning compare — xe owner (40d=138) vs xe anh em (40d=162)

> **Loại:** Diagnostics · **Trạng thái:** Current · **Ngày:** 2026-08-19
> **Nguồn:** readback `hud-compare.bat` (navopen getraw) hai xe + logcat lúc HUD hiện nav.
> **⚠ Kết luận này SỬA ADR 0002** (cờ `0x38B00030` là SAI). Xem §Kết luận.

## Câu hỏi
Vì sao HUD kính lái hiện nav trên **xe anh em** mà **không** trên **xe owner** — khi cả hai chạy **cùng app ClusterNav + GMaps**?

## Bằng chứng

### 1. App ĐẨY ĐƯỢC nav lên HUD (đã xác lập, không cần verify lại)
- HUD xe anh em (40d=162) hiện nav là **do chính app ClusterNav này + GMaps**; **trước khi cài app, HUD anh em KHÔNG có nav**. (Owner xác nhận trực tiếp.)
- Cùng **một APK / một code** trên cả hai xe ⇒ tín hiệu + đường data app phát ra **giống hệt 1-1**. Không có khác biệt phía app → không cần capture logcat xe owner để "so app-side".

### 2. Đường nav lên HUD = `AmapService` (cầu OEM) qua instrument-guide
Log xe anh em lúc HUD hiện nav (PID 3671 = `AmapService`), ghi **thành công, 0 reject**:

| feature-id | tên (OEM `Instrument.java`) | giá trị |
|---|---|---|
| `0x43E0003A` | INSTRUMENT_SEND_NAVI_STATUS | 2 |
| `0x43FA1008` | INSTRUMENT_TARGET_NEXT_PATHNAME (tên đường) | text UTF-16LE (cuộn từng frame) |
| `0x43F02018` | INSTRUMENT_NAVI_TRIP_INFO_MINUTE | 17 |
| `0x43F0201E` | INSTRUMENT_NAVI_TRIP_REMAINING_SECOND | 0 |

- Song song: `AmapService: GuideInfo.naviState: 1`.
- **App mình ghi ĐÚNG các feature này**: `BydHal.kt:284` (NAVI_STATUS=2), `:290` (PATHNAME), `:312/:313` (MINUTE/SECOND). ⇒ app KHÔNG thiếu register nào.

### 3. Cờ 38B (HUD coding đọc được) — GIỐNG HỆT hai xe
| reg | tên | owner | anh em |
|---|---|---|---|
| `38B00015` | HUD_CONFIG (1=W-mode kính) | 1 | 1 |
| `38B0001C` | HUD_SWITCH_STATUS | 1 | 1 |
| `38B00028` | HUD_NAV_CONTENT_STATUS | 1 | 1 |
| `38B0001E` | HUD_ADAS_STATUS | 1 | 1 |
| `38B00020` | — | 0 | 0 |
| `38B00022` | — | 0 | 0 |
| `38B00018` | — | −10013 | −10013 |
| **`38B00030`** | **HUD_NAV_MAP_CONFIG** | **−2147482648** | **−2147482648** |
| `38B0002E` | HUD_NAV_MAP_STATUS | (n/a baseline) | −2147482648 |

### 4. HUD owner render tốt — chỉ LỚP NAV tắt
HUD kính owner hiện đầy đủ: **tốc độ, giới hạn tốc độ (ADAS cam), ADAS, cuộc gọi + phút:giây**. **Không hiện nav.**
⇒ phần cứng HUD + render text/số chạy tốt (đếm phút:giây = render được cả text lẫn thời gian); chỉ riêng **lớp nav** không lên.

### 5. Khác biệt DUY NHẤT bắt được giữa hai xe
- **`40d` variant: owner = 138, anh em = 162.** `gbClientVersion` cùng `6125f`. Anh em `ro.build.region = ROW`.

## Kết luận (SỬA ADR 0002)
1. **App ClusterNav ĐẨY ĐƯỢC nav lên HUD kính** — chứng minh trên xe anh em (cùng app + GMaps, không đổi gì).
2. **`0x38B00030` KHÔNG phải cờ quyết định.** Cả hai xe đọc `−2147482648`, mà HUD anh em vẫn hiện nav. Giả thuyết cũ ("`readSelfLearnState()` chỉ bật khi `config==1` → owner bị chặn bởi 38B00030") **bị xe thật bác bỏ**. `38B00030` gate một cơ chế khác (cluster→HUD self-learn mirror) mà **không xe nào dùng**; đường nav thật đi qua `AmapService`/instrument-guide (43E/43F).
3. **Chặn nằm phía XE — biến thể `40d` 138 vs 162** (hoặc coding không đọc được qua getraw), **KHÔNG phải app**, **KHÔNG phải 38B00030**. Mọi cờ 38B đọc được đều giống nhau giữa hai xe.
4. **Kết luận "không sửa được qua app" VẪN ĐÚNG** — thực ra được **củng cố**: cùng app chạy trên xe coding khác thì lên HUD. **Mở khoá:** re-code xe owner sang provisioning của biến thể 162 (dealer/OBD-UDS variant coding), **KHÔNG qua adb/app/no-root**.

## Còn mở (trace-den-tan-cung — chưa đóng cửa)
- **Chưa xác định** byte/flag coding cụ thể nào khác giữa 138 và 162 cho HUD-nav; nó **không nằm trong cờ 38B đọc được**. Cần: so coding đầy đủ hai xe bằng công cụ chẩn đoán (variant coding dump), hoặc bảng tra ý nghĩa `40d` 138 vs 162. Đây là việc **coding XE**, không phải app.
- Điều kiện mở khoá (để owner quyết): mang xe tới nơi có tool coding BYD, set provisioning HUD-nav theo biến thể 162.

## Nguồn
- Owner baseline: `hud-xe-minh.txt` (2026-08-18).
- Anh em: `hud-compare-2.txt` (2026-08-19; 40d=162, region ROW).
- OEM tên feature-id: DiCarServer `Instrument.java`.
- App writes đối chiếu: `BydHal.kt` (dòng 284/290/312/313).
