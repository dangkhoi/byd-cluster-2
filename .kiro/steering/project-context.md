# Project Context — ClusterNav 2.0 (luôn-bật)

> **Trạng thái**: Current · **Cập nhật**: 2026-08-24 · **Mục đích**: Tóm tắt luôn-bật (kiến trúc + bản đồ nguồn nav + HAL/CAN + map file + trạng thái) để phiên sau KHÔNG phải đọc lại toàn code.
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
- **VietMap** (`vn.vietmap.live`): (a) **widget RemoteViews = speed/giới-hạn/giới-hạn-sắp-tới + cự ly, chạy NỀN** (slot `SPEED_LIMIT`/`ALERTS`/`ALERT_FULL`=VMAlertWidgetProvider); (b) **a11y content-desc = turn/đường/ETA nhưng FOREGROUND-only** — Flutter ⇒ KHÔNG có resource-id, parse ở `VietMapDescParser` (:core) → publish `NavViewIdSource` (B3.40); (c) **mũi tên = screen-capture** qua `NavGlyphLocator` (dò glyph động, bất biến dpi/size/vị-trí) + registry riêng `WazeArrowRegistry.VIETMAP_INK` 34 template sinh từ asset APK 3.3.4 (B3.43). **B3.47 vòng 3 (08-23) đã thay cổng cỡ tuyệt đối bằng tiêu chí TỈ SỐ** — gỡ `MAX_DP`, nới `MAX_FILL` 0.35→0.60, thêm `MIN/MAX_ASPECT` [0.35, 1.45] + `MAX_LEFT_ANCHOR` 2.0 (khoảng cách mép-trái đo bằng chính cỡ đảo). [ĐO] bằng locator Kotlin thật trên 87 khung: **dò đúng 27→86**, **đảo mồi 60→0**, tới mã AMAP **15→39**; Waze bbox không đổi một pixel. Đảo mồi = icon POI xe buýt TRÊN BẢN ĐỒ (603,78)-(642,117) qua sạch cả 4 cổng cũ ⇒ giả định "không dò được ⇒ im lặng" từng SAI. Khoá: `VietMapGlyphGateTest` + `NavGlyphLocatorTest.bbox Waze khong doi mot pixel nao`; (d) camera = map trên SurfaceView → screen-capture. ⚠ **KHÔNG còn ở roster kênh notification** từ B3.44 (`NavApps.NOTIFICATION` = chỉ GMaps) — chính noti của nó từng đóng mốc DATA và khoá mất kênh ảnh của chính nó.
- **Waze / WazeMod** (`com.waze` / `com.chisadin.wazemod`): **KHÔNG có kênh data nền** (đo 08-22: noti chỉ có `tickerText=Waze`) ⇒ cũng KHÔNG ở roster notification (B3.44). Hai kênh THẬT, cả hai đều **cần app visible**: (a) **view-id a11y** (clone OpenBYD) — `navBarDistance`/`navBarStreetLine`/`lblArrivalTime`… , ⚠ tiền tố id là tên package trong `resources.arsc` = **`com.waze` cho CẢ bản mod** (WazeMod DUAL manifest `com.chisadin.wazemod` nhưng arsc `com.waze`) ⇒ một tiền tố phủ cả hai (B3.33); (b) **screen-capture mũi tên** → `WazeArrowRegistry.WAZE_INK` (4 template). HLP-logcat đã **gỡ hẳn** khỏi code+UI (B3.21/B3.30: 0 dòng log khi đang dẫn, hardware-gated).

## 3. HAL / CAN facts (đã proven on-car / RE)
- **HUD kính ZIN nav = gate firmware của XE, KHÔNG phải app** (ADR 0002, **sửa ×2 2026-08-19**). **App ĐẨY nav lên bus ĐÚNG** — proven vì **HUD Taobao aftermarket** trên xe anh em hiện nav bằng **chính app này + GMaps** (đường render ĐỘC LẬP, đọc bus tự vẽ; **cụm cũng hiện nav**). ⚠ **HUD Taobao ≠ HUD zin**: nó KHÔNG bác được cờ gate zin. **`0x38B00030` (INSTRUMENT_HUD_NAVIGATION_MAP_CONFIG, consumer `readSelfLearnState()==1`) VẪN là nghi phạm gate HUD-ZIN, CHƯA bị bác.** `40d` 138(owner) vs 162(anh em) chỉ **tình cờ** — khác biệt THẬT = HUD zin vs HUD Taobao, không phải con số 40d. **Hai đường ra:** (a) HUD Taobao = đã chạy; (b) HUD zin = provisioning firmware (`0x38B00030` ± cờ khác) qua tool coding. Đừng "fix" trong code. Chi tiết + xếp hạng đường: `docs/diagnostics/factory-hud-nav-RE-avenues-2026-08-19.md`.
- **`NOT_PROVISIONED_RC = -2147482648`** (= `Int.MIN_VALUE + 1000` = `0x800003E8`; **KHÁC** `Int.MIN_VALUE`). BydHal cache per-feature-id gặp rc này → skip frame sau (hết spam `no permission device 1007`). Xe provision oversea (Sealion 6) không bao giờ nhận sentinel → vẫn ghi.
- **guide** `INSTRUMENT_GUIDE_INFO_SIMPLE_SET`: **`0x43F01010` (domestic)** / **`0x1F701010` (oversea)** — cụm-centre + HUD Taobao đọc (HUD zin chỉ đọc khi lớp nav provisioned); app ghi CẢ 2 họ.
- **`SET_NAVI_SCREEN_STATUS_SET = 0x4C10E015`** (BYDAutoSettingDevice); `NAV_SCREEN_MODE_ON = 3`.
- **`Maneuver.toHudIcon()` vòng xuyến** (CAN ghi-thẳng, OpenBYD `w40`+`HudController`): **15=trái · 18=phải · 20=thẳng/generic · 22=u-turn** (CCW/VN); CW=16/17/19/21; có số lối ra → **24+N** (25..34). `ROUNDABOUT_EXIT`→HUD **24**. `toAmapIcon()` vòng xuyến (mọi hướng) = **11 generic** (cụm-strip không có glyph hướng).
- **Nav-guide registers (43E/43F: NAVI_STATUS/PATHNAME/TRIP) ghi rc=0** → nuôi cụm-centre + **HUD Taobao aftermarket** (đọc bus tự vẽ). Đây là **đường content** (đúng, đã chạy). Với **HUD zin**, content 43E/43F chỉ render khi **lớp nav được provision** — nghi phạm gate = `0x38B00030` (INSTRUMENT_HUD_NAVIGATION_MAP_CONFIG, `readSelfLearnState()==1`), **ghi thẳng bị reject** (đọc-only, set qua UDS coding). ⚠ đừng kết luận "38B00030 không liên quan": nó CHƯA bị bác cho HUD zin (bằng chứng anh em là HUD Taobao, đường render khác).
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
- `core/.../navigation/NavApps.kt` — **một nguồn sự thật** cho roster app nav: `ALL` (cổng a11y, khớp XML `nav_accessibility_config.xml`) · `NOTIFICATION` (chỉ GMaps) · `DESC_ONLY` (VietMap) · `GMAPS`/`WAZE`/`VIETMAP`.
- `core/.../navigation/screencapture/NavGlyphLocator.kt` — dò bbox mực của glyph mũi tên: đảo sáng + vành tối 4 phía + **sàn** dp + **hai tỉ số hình dạng** + **neo trái** + trái-nhất. Bất biến với dpi/size/vị-trí cast (B3.47 vòng 3 gỡ hẳn trần dp).
- `core/.../navigation/WazeArrowRegistry.kt` — registry quy ước **bbox-mực** (4 Waze + 34 VietMap). TÁCH CỨNG khỏi 38 mục GMaps quy ước *khung-vẽ* của `ManeuverSignature` (B3.46).
- `core/.../navigation/NavViewIdSource.kt` — holder dữ liệu a11y (Waze view-id + VietMap content-desc); `Reading` composite chống đọc-rách.
- `core/.../navigation/{NavFrameIdentity,NavSourceDwell,TurnDistancePlausibility}.kt` — 3 guard scope B: một-package-một-khung · dwell 3 nhịp chống nhảy nguồn · chặn cự ly nhảy phi lý.
- `core/.../navigation/screencapture/NavWindowPump.kt` — **nhịp định kỳ** cho vòng enum cửa sổ a11y (B3.13r): một throttle 800 ms dùng chung cho CẢ đường event LẪN đường nhịp (**trần đỉnh** enum/giây không đổi; **trung bình thì tăng** — xem B3.13r-cost) · tự tắt sau 4 nhịp không thấy cửa sổ nav · mồi lại bằng `arm()` từ event a11y **và** `onServiceConnected` (hai đường độc lập vòng enum). Bệnh nó chữa KHÔNG phải "hết tươi 3 s ⇒ cụm trống" (nhịp chụp tự nuôi qua `SourceArbiter.shouldFeed(…, IMAGE)`) mà là **VÒNG TRÒN**: kênh ảnh im > `STALE_MS` ⇒ cổng `ScreenCaptureNavSource.tick` đóng, mà thứ duy nhất mở lại cổng nằm SAU chính nó.
- `app/.../NavOutputOwner.kt` — owner đường ẢNH: quyết định push + tự assert op-39 dựng bề mặt cụm (B3.45).

## 5. Trạng thái (2026-08-24)
- **Branch** `feat/speed-limit-badge-hal-hud` · mốc đã push `40474af` (v1.13) · **~40 file sau đó CHƯA commit** (F1/F2/F3/F4-bước-1). **main = `f7843c0`** — không đụng khi chưa PASS on-car.
- **APK đã giao owner**: v1.13 (mốc) → v1.14 (badge+phím thoại+gán nhiều phím) → **v1.15 code 16** `ClusterNav2.0-v1.15-hud-vietmap-waze-20260824.apk` ← **chờ owner test trên xe**.
- **Test [ĐO]**: `./gradlew test --rerun-tasks --continue` ⇒ **2126 bài, 0 lỗi** cả 5 module. `org.gradle.parallel=true` bật 08-24 ⇒ 127 s (trước 200 s). Bẫy E6 (test tự phá seal) ĐÃ VÁ — không cần `git checkout` seal nữa.
- **KIẾN TRÚC — owner duyệt 08-24** (`docs/specs/nav-input-output-architecture.html`): *nhiều cách nhận tín hiệu (noti · a11y · đọc màn hình) → **MỘT cửa vào tại một thời điểm** (`NavRepository.ingest`/`ingestContent` → `NavigationSessionCoordinator`) → các đầu ra (HUD kính lái · cụm giữa+ETA · dải làn · chip camera)*.
  - **Bước 1/3 XONG**: đường ảnh (VietMap/Waze) nay vào `ingestContent` — CÙNG cửa với notification ⇒ đi hết chuỗi chốt-phiên → cụm giữa → HUD. [ĐO] `BydHal.pushNavigation` mất hết call site; `writeNavFrame` còn **đúng 1** chủ (`NavigationHudOwner`) ⇒ đóng B3.54.
  - **Còn nợ (F7)**: dải làn + chip camera vẫn đi thẳng từ `NavOutputOwner`; sau đó gỡ file đó. Hai kênh này ghi thanh ghi RIÊNG nên KHÔNG gây lỗi — dọn kiến trúc thuần, owner hoãn tới sau khi v1.15 xác nhận trên xe.
- **CHƯA on-car**: toàn bộ B3.40→B3.56 + F1→F4 mới off-car/emulator. `CLAUDE.md §14` tầng 1 chưa xanh.
- **OPEN**: (a) v1.15 chờ đo trên xe — VietMap dẫn thì HUD kính lái có mũi tên+cự ly không; (b) vòng xuyến generic = OEM-render (C2); (c) HUD kính ZIN = gate firmware `0x38B00030` chưa bác (D6); (d) F7 dọn kiến trúc; (e) B3.52 phủ sóng glyph VietMap 39/87 ra được mã; (f) D4 scrub secret cũ khỏi git history (cần owner duyệt force-push).

## 6. Cách làm việc — owner chốt 2026-08-24
- **Báo cáo bằng ngôn ngữ người dùng**, KHÔNG trộn thuật ngữ/tên file/mã việc vào câu văn (owner: *"viết kiểu 50/50 thế này dọc không hiểu gì cả"*).
- **Tài liệu CHỈ lưu local** — cấm publish claude.ai artifact / dịch vụ ngoài, kể cả private.
- Nhiều agent ⇒ chạy **TUẦN TỰ**, không song song trên cùng thư mục dự án (tranh khoá gradle).
- Agent chạy test: lọc lớp lẻ (`--tests`) lúc lặp; chỉ agent cuối chạy đủ 5 module.
