# Project Context — ClusterNav 2.0 (luôn-bật)

> **Trạng thái**: Current · **Cập nhật**: 2026-08-19 · **Mục đích**: Tóm tắt luôn-bật (kiến trúc + bản đồ nguồn nav + HAL/CAN + map file + trạng thái) để phiên sau KHÔNG phải đọc lại toàn code.
> *Derived* từ repo (nguồn-sự-thật). Chi tiết: `docs/README.md` (INDEX) · `docs/PROJECT-BACKLOG.md` (task) · `docs/diagnostics/*`. App standalone `com.byd.clusternav2`, JDK 17, compile/target SDK 37, minSdk 29.

## 1. Kiến trúc — ĐÚNG 2 nhánh (không chia sẻ runtime/state/executor/journal)
- **Navigation + HUD**: 1 nguồn nav (SourceArbiter chọn) → `NavigationSessionCoordinator` (authoritative) → **`Maneuver` trung lập** (1 quyết định hướng rẽ) → 3 đầu ra encode ĐỘC LẬP:
  - **cluster-lane** (làn zin) qua broadcast AMAP — `toAmapIcon()`.
  - **cluster-centre "Giữa + ETA"** qua HAL `BydHal.writeNavFrame` (owner DUY NHẤT = `NavigationHudOwner`) — `toHudIcon()`. Chỉ ghi khi `navOnlyMode` (Cast master OFF).
  - **windshield HUD** (kính lái) — cùng họ HAL guidance; bị gate bởi coding xe (xem §3).
- **Cluster Cast**: state/journal/cast ĐỘC LẬP (`SimpleCastRuntime`, 4-state IDLE→PROJECTING→CASTING→RETURNING, 1 nút nổi cast/return). KHÔNG dùng chung state/executor với Nav.
- **Home = renderer/dispatcher**, KHÔNG orchestrator.

## 2. Bản đồ NGUỒN NAV (quan trọng — tham chiếu khi làm B3 screen-capture)
- **GMaps** (`com.google.android.apps.maps` + `app.revanced.android.apps.maps`): **notif = nav ĐẦY ĐỦ, chạy NỀN** + arrow bitmap. Nguồn chính + **ground-truth cự ly** (nội suy bám notif).
- **VietMap** (`vn.vietmap.live`): (a) **widget RemoteViews = speed/giới-hạn/giới-hạn-sắp-tới + cự ly, chạy NỀN** (slot `SPEED_LIMIT`/`ALERTS`/`ALERT_FULL`=VMAlertWidgetProvider); (b) **a11y content-desc = turn/đường/ETA nhưng FOREGROUND-only** (VietMap phơi nav ở content-desc, KHÔNG phải text) → nền thì stale; (c) camera = map trên SurfaceView → cần **screen-capture (B3)**.
- **Waze / WazeMod** (`com.waze` / `com.chisadin.wazemod`): **KHÔNG có kênh data nền**. Nav qua **screen-capture mũi tên (B3, học OpenBYD `WazeArrowCaptureService`)** HOẶC WazeMod HLP-logcat (cần cấu hình) HOẶC ESP32 HUD.

## 3. HAL / CAN facts (đã proven on-car / RE)
- **HUD kính nav = coding/variant XE, KHÔNG phải app** (ADR 0002, **sửa 2026-08-19**). **App ĐẨY ĐƯỢC nav lên HUD** — proven trên xe anh em (`40d`=162, **chính app này + GMaps**, qua `AmapService`→`43E0003A/43FA1008/43F02018/0201E`). Xe owner (`40d`=138) HUD hiện speed/limit/ADAS/call mà **không hiện nav** → chặn **phía XE**. **`0x38B00030` KHÔNG phải cờ** (cả 2 xe đọc `-2147482648`, anh em vẫn lên HUD). Cờ coding thật (138 vs 162) **chưa xác định**; đừng "fix" trong code. Chi tiết: `docs/diagnostics/hud-provisioning-compare-2026-08-19.md`.
- **`NOT_PROVISIONED_RC = -2147482648`** (= `Int.MIN_VALUE + 1000` = `0x800003E8`; **KHÁC** `Int.MIN_VALUE`). BydHal cache per-feature-id gặp rc này → skip frame sau (hết spam `no permission device 1007`). Xe provision oversea (Sealion 6) không bao giờ nhận sentinel → vẫn ghi.
- **guide** `INSTRUMENT_GUIDE_INFO_SIMPLE_SET`: **`0x43F01010` (domestic)** / **`0x1F701010` (oversea)** — cả HUD kính + cụm-centre đọc; app ghi CẢ 2 họ.
- **`SET_NAVI_SCREEN_STATUS_SET = 0x4C10E015`** (BYDAutoSettingDevice); `NAV_SCREEN_MODE_ON = 3`.
- **`Maneuver.toHudIcon()` vòng xuyến** (CAN ghi-thẳng, OpenBYD `w40`+`HudController`): **15=trái · 18=phải · 20=thẳng/generic · 22=u-turn** (CCW/VN); CW=16/17/19/21; có số lối ra → **24+N** (25..34). `ROUNDABOUT_EXIT`→HUD **24**. `toAmapIcon()` vòng xuyến (mọi hướng) = **11 generic** (cụm-strip không có glyph hướng).
- **Nav-guide registers (43E/43F: NAVI_STATUS/PATHNAME/TRIP) ghi rc=0** → nuôi cụm-centre + (trên xe provision như 162) **cả HUD kính**. `0x38B000xx` từ chối ghi nhưng **KHÔNG liên quan** đường nav-lên-HUD (đường thật đi qua 43E/43F, không qua 38B).
- **Bug owner "vòng xuyến generic"** = **OEM RENDER-side**, đặc thù variant xe owner (`vehicle_40d` owner=138 vs bạn=162). App gửi **ĐÚNG CAN 18** (data owner + bạn xác nhận). → glyph-test cần data owner on-car (C2).

## 4. Map file chính
- `core/.../navigation/Maneuver.kt` — enum maneuver trung lập + `toAmapIcon`(cụm)/`toHudIcon`(HUD)/`fromAmapIcon`.
- `core/.../navigation/ManeuverSignature.kt` — phân loại GMaps large-icon (chữ ký) → maneuver/mã AMAP.
- `app/.../NavNotificationListener.kt` — listener notif 5 gói nav; gate+parse+fan-out; sở hữu speed-sign + VietMap bridge + Waze HUD.
- `app/.../NavRepository.kt` — facade runtime nav authoritative; `ingest`→coordinator→lane + centre(HAL) + HUD.
- `app/.../NavigationHudOwner.kt` — owner DUY NHẤT ghi cụm-centre HAL (Giữa+ETA) + keep-alive.
- `app/.../modules/hal/BydHal.kt` — hạ tầng HAL reflection: `writeNavFrame` (icon/cự ly/đường/ETA domestic+oversea) + rejection cache + register consts.
- `core/.../vietmapwidget/VietMapWidgetModels.kt` — model snapshot VietMap widget (speed/limit/upcoming, per-slot freshness).
- `app/.../speedbadge/SpeedBadgeOverlay.kt` — overlay badge tốc-độ/giới-hạn trên cụm (display 1), lifecycle event-driven + badge "sắp tới".
- `app/.../modules/clustercast/simplified/SimpleCastRuntime.kt` — runtime Cast 4-state.
- `app/.../modules/wazehud/WazeHudSource.kt` — nguồn Waze HUD (poll HLP).

## 5. Trạng thái
- **Branch** `feat/speed-limit-badge-hal-hud` (HEAD `3745046`, **đã push `origin`, sync**); **main = `f7843c0`** (1.0 ổn định — KHÔNG đụng tới khi chưa PASS exact-build on-car + owner duyệt).
- **Validate off-car (PASS)**: mũi tên **18/18 đúng** (send-side); **nội suy cự ly đúng** (median 0 vs notif GMaps; chuyến sáng 95% <1m). Xem `docs/diagnostics/arrow-validation-*` + `distance-interpolation-validation-*`.
- **OPEN**: (a) vòng xuyến generic trên cụm owner = **OEM-render** → glyph-test **on-car** (C2); (b) HUD kính nav = **coding/variant `40d` 138 vs 162** (C3 đã so xong — `0x38B00030` bị bác; app đẩy được HUD trên xe 162; cờ cụ thể chưa xác định → cần variant-coding dump/tool); (c) **screen-capture** Waze arrow + VietMap camera (**B3**, off-car spec trước).
