# Multi-app nav-source channels + overlay findings

> **Trạng thái**: Current · **Ngày**: 2026-08-20 · **Loại**: Diagnostics (finding + bằng chứng)
> **Mục đích 1 dòng**: Chốt (bằng bằng chứng emulator) mỗi app dẫn đường phơi thông tin gì ở NỀN vs cần VISIBLE, và khung kiến trúc ĐÚNG để phối GMaps (dẫn) + VietMap (data VN) trên cụm.

## 0. Mục tiêu owner (chốt cuối phiên)
- **GMaps = dẫn đường** (routing tốt) → cast lên **cụm**.
- **VietMap = CHỈ lấy DATA Việt Nam** GMaps thiếu: **tốc độ, giới hạn, camera phạt nguội, biển cảnh báo** (làn = de-prioritize).
- Cần **≥2 nguồn chạy SONG SONG**: GMaps dẫn + VietMap cấp data VN.
- **Khung ĐÚNG**: KHÔNG cần overlay/bong bóng của VietMap trên cụm. Cần **DATA** của VietMap → **app mình tự vẽ** (speed badge + chip camera/cảnh báo) lên cụm, **cạnh** nav GMaps. (two-track.)

## 1. Ma trận kênh (bằng chứng emulator 2026-08-20)
| Thông tin | GMaps | VietMap | Waze/WazeMod |
|---|---|---|---|
| **Mũi tên rẽ → HUD (NỀN)** | ✅ **notification** (icon+cự ly+đường, chạy nền hẳn) | ❌ a11y content-desc **foreground-only** (nền = stale) | ❌ HLP cần HUD BLE (xem §3) |
| Đường + cự ly (nền) | ✅ notif | ✅ notif (đường ở title, cự ly ở text) | ❌ |
| **Tốc độ + giới hạn (nền)** | ~yếu ở VN | ✅ **widget RemoteViews** = speed badge (ĐÃ CHẠY) | ❌ |
| **Camera/cảnh báo + cự-ly (nền)** | ❌ | ✅ **widget ALERTS/ALERT_FULL** (cự-ly + ảnh cảnh báo) HOẶC bong bóng-a11y ("98m") | ❌ |
| **LOẠI cảnh báo (camera vs cấm?)** | ❌ | ⚠️ là **ICON (ảnh)** — a11y chỉ cho cự-ly, không cho type → phải **match hash icon** | ❌ |
| **Turn-glyph · LÀN (đồ hoạ)** | chỉ khi VISIBLE (capture) | chỉ khi VISIBLE (capture) | chỉ khi VISIBLE (capture) |

**Kết luận nền tảng:** **LÀN + mũi-tên-đồ-hoạ** không app nào phơi ở NỀN — **bắt buộc app VISIBLE** (foreground/cast lên cụm/split) để screen-capture. Tốc độ + camera/cảnh-báo (data + cự-ly) thì VietMap phơi được ở NỀN qua widget/bong bóng.

## 2. VietMap bong bóng (floating overlay) — bằng chứng
- Là window `ty=APPLICATION_OVERLAY` (appop SYSTEM_ALERT_WINDOW), **dùng TextView thật** → **a11y ĐỌC được không cần chụp**: `[VIETMAP LIVE | 0 | km/h | 50 | 98m]` = tốc độ + giới hạn + cự-ly-cảnh-báo. **KHÔNG có turn/làn.**
- **Chỉ bung khi**: floating BẬT (toggle trong VietMap) **VÀ** VietMap bị nền **từ màn CHÍNH**. Tắt floating → không có window (a11y đọc rỗng).
- **LUÔN vẽ ở display 0 (default)** — kể cả khi activity VietMap chạy trên display phụ (test: activity ở display 3, overlay vẫn bung ở **display 0**). ⇒ **VietMap hardcode overlay vào default display**; từ ngoài **không ép/di-chuyển/ẩn** được (window của app khác).

## 3. Waze HLP-logcat — CÓ thật sự lấy được không? → KHÔNG (nếu không có HUD BLE)
- `WazeHudSource`: HLP/1 JSON mang `trn/spd/lim/dst/st/eta/alr(cảnh báo camera)` — **KHÔNG có làn**.
- **Nhưng `WazeManager` doc**: HudLink **chỉ EMIT khi có BT/BLE HUD peer kết nối**. Head unit trần (không ESP32/máy thứ 2) → **không bắn frame** → source rỗng. Chưa proven live on-car.
- ⇒ **KHÔNG nên để "Waze (HLP)" là nguồn đang-chạy** trong options; nếu giữ → gắn nhãn **"cần HUD BLE"**.

## 4. Emulator display phụ (để test overlay cụm off-car)
- `settings put global overlay_display_devices "1920x720/213"` tạo display phụ (id tự gán 2/3…) **NHƯNG render dạng overlay đè bán-trong-suốt lên màn chính → NUỐT touch, không tách ra được** (bản chất dev-feature). Gỡ: `settings delete global overlay_display_devices`.
- Muốn display phụ RIÊNG (bấm được): **Extended Controls → Displays → Add secondary display** (cửa sổ riêng).
- Test tự động thì không cần: inject frame + `screencap -d <id>` qua adb.

## 5. Ý tưởng đã BÁC (có lý do)
- **"Lừa VietMap hiện overlay lên cụm"** (chạy VietMap ở display 1): BÁC — overlay luôn về display 0 (§2). Kể cả bung được cũng không ở cụm.
- **Clone overlay pixel lên cụm**: kỹ thuật chạy được (capture display-0 → blit display-1) NHƯNG **THỪA** — bản gốc vẫn ở display 0 (không xoá được) → 2 màn cùng hiện. Owner bác.
- **Case-4 (đọc app background bằng ảnh)**: BÁC từ B3.12 (screencap chỉ thấy foreground).

## 6. Khung ĐÚNG + việc còn lại
- **Two-track**: GMaps (notification) → nav/HUD cụm · VietMap (widget) → **DATA** (tốc độ ✅ đã có = speed badge; camera/cảnh-báo 🔧 cần thêm chip) → **overlay của MÌNH** trên cụm, song song.
- **Việc thực chất còn thiếu**: đọc `VietMapRoadAlert` (đã parse: `distanceMeters` + ảnh cảnh báo) → **vẽ chip "camera/cảnh báo + cự-ly"** trên cụm cạnh speed badge. Loại "camera" cụ thể = match hash icon (thu 1 lần). Ưu tiên **widget** (bền, không cần bong bóng).
- **Làn**: park (cần app visible; owner de-prioritize).

## 7. Code phiên này (đã commit, nhánh `feat/speed-limit-badge-hal-hud`, chưa push)
- Full-B3 T1b/T3/T4/T5/T6/T7 + P0 fix w960dp + senior review [P2] onDestroy-leak; data-flow doc `docs/specs/b3-data-flow.html`; overlay display-id override (`Prefs.overlayDisplayId`) + **debug frame-inject** (`DEBUG_NAV_FRAME`, tham số hoá) + **debug window-dump** (`DEBUG_DUMP_WINDOWS`) — cả hai gated `BuildConfig.DEBUG` (loại khỏi release OTA).
- **Overlay render PROVEN trên display 0** (inject frame → dải làn + chip camera vẽ đúng).

## 8. On-car ground truth (2026-08-21, read-only qua adb network, shell uid 2000 không root)
- Xe **BYD AUTO, Android 10**; app `com.byd.clusternav2` = **1.1** (OTA release — CHƯA có full-B3 feat).
- **Display 0 (màn chính IVI): 1920×1080** (density 240). **Display 1 (CỤM): 1920×720** (density 320) — cụm là **virtual `fission` surface** (`com.xdja.containerservice`) → khớp transport `fission -d0/-d1` trong code.
- ⚠️ **CALIB GAP**: crop rect mũi tên **B3.9 = 960×720 SAI cho xe** (cụm 1920×720, chính 1920×1080). Phải **recalib theo kích thước thật**. `ClusterOverlayHost` default **1920×720 KHỚP cụm** ✓.
- **Setup THẬT của owner (xác nhận qua ảnh)**: **GMaps cast lên CỤM** (cửa sổ có min/max/close + letterbox, KHÔNG full 1920×720) · màn chính = home + **speed badge ClusterNav (VietMap-fed) CHẠY TỐT** (góc trên-phải: "0 km/h" + biển "50" + ô upcoming "—").
- GMaps-cast trên cụm là **WINDOWED/letterbox** → khi dẫn thật, mũi tên/làn nằm trong khung GMaps có **offset chrome+letterbox** → recalib phải trừ offset này.
- **screencap qua network-adb-shell (không root) CHỤP được CẢ 2 display** (ghi /sdcard → pull) — dùng được để recalib/test off nhanh; app tự chạy vẫn cần dadb-root cho cache app-private.
- Apps trên xe: vietmap.live, com.waze, com.chisadin.wazemod, google maps, here, `com.example.amapservice` (nav cụm AMAP).
