# ClusterNav 2.0 — Project Backlog

> **Trạng thái**: Current · **Cập nhật**: 2026-08-19 · **Mục đích**: Nguồn DUY NHẤT cho task (ID · việc · trạng thái · ngày bắt đầu/kết thúc).

> File quản lý công việc chung của dự án. Cập nhật status khi làm.
> **Legend:** ✅ DONE · 🔧 REFINE (đã làm, cần chỉnh) · 🔲 TODO (làm off-car được) · ⛔ BLOCKED (cần xe / anh em) · 📋 BACKLOG (ưu tiên thấp)
> Branch làm việc: `feat/speed-limit-badge-hal-hud` (main = `f7843c0` 1.0 ổn định, KHÔNG đụng đến khi PASS on-car).

---

## A. ĐÃ XONG (DONE)

| ID | Việc | Trạng thái | Bắt đầu | Kết thúc | Ghi chú |
|----|------|-----------|---------|----------|---------|
| A1 | Badge placement UI (kéo-thả) + gom 1 overlay + full VietMap logging + Waze tag fix | ✅ | 2026-08-17 | 2026-08-18 | commit `d4dd6d5` |
| A2 | Badge lifecycle fix (idempotent init + DisplayListener) + badge toggle + nav_access source-tag + screenshot fission-index + VietMap alert fix | ✅ | 2026-08-18 | 2026-08-18 | commit `bd09a4d` |
| A3 | Bind VMAlertWidgetProvider → capture "giới hạn sắp tới + cự ly" (upLimit/upDist) | ✅ | 2026-08-18 | 2026-08-18 | commit `36f6bf0`; V3 PASS (data thật 50/60/70/80 + đếm lùi) |
| A4 | Raw-notif capture (mọi notif của 5 gói, tag pkg) | ✅ | 2026-08-18 | 2026-08-18 | commit `16f7214` |
| A5 | a11y contentDescription capture (VietMap phơi nav ở content-desc, không phải text) | ✅ | 2026-08-18 | 2026-08-18 | commit `53265c5`; verify on-car; **chỉ foreground** |
| A6 | Per-app/per-channel log analyzer (`scripts/analysis/analyze_drive_logs.py`) | ✅ | 2026-08-18 | 2026-08-18 | commit `d6de8fd` |
| A7 | HUD-compare toolkit (navopen-v4 getraw) + guide Windows + thêm cờ 20/22/18 | ✅ | 2026-08-18 | 2026-08-18 | commit `ab56362`,`7027a21`; gửi anh em |
| A8 | Logging/ảnh mặc định OFF + storage cap <150MB | ✅ | 2026-08-18 | 2026-08-18 | commit `11751ba`; fix tràn bộ nhớ xe |
| A9 | Upcoming speed-limit badge (badge thứ 2 + cự ly, neo dưới badge chính) | 🔧 | 2026-08-18 | 2026-08-18 | commit `11751ba`; **CẦN CHỈNH design → xem B2** |
| A10 | Validate mũi tên 1-1 (18/18 đúng) + nội suy cự ly (đúng, bám notif) | ✅ | 2026-08-18 | 2026-08-19 | commit `1d2387f`; docs/diagnostics/ |
| A11 | HUD baseline read (0x38B00030 = NOT provisioned) + write-attempt (mọi write bị từ chối → coding-locked) | ✅ | 2026-08-18 | 2026-08-18 | on-car; `~/Desktop/hud-xe-minh.txt` |
| A12 | Push feat lên origin (public) + scrub topology | ✅ | 2026-08-19 | 2026-08-19 | `3745046`; zero IP footprint |

---

## B. TODO — off-car (làm được ngay)

| ID | Việc | Trạng thái | Bắt đầu | Kết thúc | Ghi chú |
|----|------|-----------|---------|----------|---------|
| B1 | **Auto-start VietMap khi mở app** — NẾU bật hiện speed badge thì tự mở VietMap lúc mở app mình (để widget có nguồn) | 🔲 | | | Owner note 2026-08-19 |
| B2 | **Chỉnh design badge "giới hạn sắp tới"**: viền XÁM, số XÁM, size **80%** badge tốc độ, đặt **chéo 45° phía DƯỚI-TRÁI** badge tốc độ (hiện đang neo "thẳng dưới") | 🔲 | | | Owner note 2026-08-19; sửa A9 |
| B3 | **Screen-capture + xử lý ảnh (học OpenBYD `WazeArrowCaptureService`)** → nguồn **Waze arrow** + **camera VietMap**. MediaProjection + PixelCopy + phân tích pixel mũi tên/icon (như ManeuverSignature) | 🔲 | | | **Feature lớn — spec trước.** 4 case bên dưới |
| B3.1 | Case 1: app dẫn đường **full màn chính** → mirror màn chính, crop vùng mũi tên | 🔲 | | | phần của B3 |
| B3.2 | Case 2: app dẫn **1/2 màn chính** (trái HOẶC phải — màn chính chia đôi) → mirror + crop đúng nửa | 🔲 | | | phần của B3 |
| B3.3 | Case 3: app dẫn **bên màn cụm** (đã cast) → PixelCopy từ SurfaceView cast | 🔲 | | | phần của B3 |
| B3.4 | Case 4: app **không active đâu** → tạo mirror / chỗ để app vẫn chạy-render mà capture được (học OpenBYD virtual display) | 🔲 | | | phần của B3; khó nhất |
| B4 | Diagnostics hygiene: đánh dấu `screenRead` INVALID khi stale (age cao) / không có road (GMaps nền) | 🔲 | | | tránh nhiễu phân tích + refine anchor rác |

---

## C. ON-CAR / BLOCKED (cần xe owner hoặc anh em)

| ID | Việc | Trạng thái | Bắt đầu | Kết thúc | Ghi chú |
|----|------|-----------|---------|----------|---------|
| C1 | Cài build mới (logging-off + upcoming-badge) lên xe owner | ⛔ | | | `~/Desktop/ClusterNav2.0-nolog-upcomingbadge-20260818.apk`; khi ở xe |
| C2 | Glyph-test vòng xuyến trên cụm owner — mã CAN nào vẽ directional (15/18/20/24/24+N) trên OEM owner | ⛔ | | | bug owner: vòng xuyến generic = OEM render (app gửi đúng CAN 18); on-car only |
| C3 | HUD provisioning compare — anh em chạy `hud-compare.bat` (USB) → cờ 0x38B00030 (kỳ vọng =1) | ⛔ | | | chờ anh em; so với baseline owner |
| C4 | Verify on-car: badge lifecycle over cast; VietMap a11y turn/đường khi dẫn; giá trị VMAlert (upLimit/upDist) | ⛔ | | | sau khi cài C1 |
| C5 | Merge feat → main | ⛔ | | | CHỈ sau khi PASS exact-build on-car + owner duyệt |

---

## D. BACKLOG (ưu tiên thấp)

| ID | Việc | Trạng thái | Bắt đầu | Kết thúc | Ghi chú |
|----|------|-----------|---------|----------|---------|
| D1 | Làm mượt nội suy lúc transition/reroute | 📋 | | | hiện đã 95% khớp <1m; ưu tiên thấp |
| D2 | `.bat` auto-reconnect khi WiFi drop | 📋 | | | tiện cho anh em |
| D3 | Viết `docs/diagnostics/hud-provisioning-compare-*.md` sau khi có readback xe anh em | 📋 | | | sau C3 |
| D4 | **Scrub secret CŨ (đã public)** khỏi HEAD + git HISTORY: mật khẩu factory DiLink (`hud-cluster-injection-findings-2026-08-10.md` ×2) + machine-username path (`handoff-2026-08-17-...md`) | 📋 | | | file CŨ đã push từ trước; scrub content hiện tại + `filter-repo` history + force-push (**cần owner duyệt**) |
| D5 | Roundabout icon coverage từ chuyến có nhiều vòng xuyến (đã có data anh em) | 📋 | | | đã đủ coverage; low priority |

---

## E. HỆ THỐNG DOC / KNOWLEDGE (nguồn-sự-thật)

| ID | Việc | Trạng thái | Bắt đầu | Kết thúc | Ghi chú |
|----|------|-----------|---------|----------|---------|
| E0 | Rule Documentation & Backlog (steering, bắt buộc 100%) | ✅ | 2026-08-19 | 2026-08-19 | `.kiro/steering/documentation-and-backlog.md` |
| E1 | **Refactor docs theo rule**: tạo `docs/README.md` INDEX + header mọi doc current + archive doc cũ + đúng 9-loại taxonomy | ✅ | 2026-08-19 | 2026-08-19 | INDEX + header (12 doc) + 9-loại; archive=0 (bảo thủ, chủ đích); docs off-car, **committed c7409b8** |
| E2 | Tạo `.kiro/steering/project-context.md` (kiến trúc + bản đồ nguồn nav + HAL/CAN facts + map file + trạng thái) | ✅ | 2026-08-19 | 2026-08-19 | 43 dòng (<150); khớp diagnostics+backlog; **committed c7409b8**; nhớ `git add -f` (.kiro gitignored) |
| E3 | Index knowledge base: `docs/` (Best) + `core/`+`app/` source (Fast) | ✅ | 2026-08-19 | 2026-08-19 | 3 KB: byd-clusternav-docs + code-core + code-app (indexing background); tra thay vì đọc lại |
| E4 | Thêm §Nhật ký triển khai vào spec template + specs hiện có; tạo `docs/decisions/` (ADR) | 🔧 | 2026-08-19 | 2026-08-19 | template `_template.html` (§9) + `decisions/` (README+3 ADR) DONE; **back-fill §Nhật ký vào 38 specs hiện có = DEFERRED** (task riêng); committed c7409b8 |

## Ghi chú nguồn nav (tổng hợp — để tham chiếu khi làm B3)
- **GMaps**: notif (nav đầy đủ, chạy NỀN) + arrow bitmap. ✅ đang dùng.
- **VietMap**: widget (speed/limit/upcoming, chạy NỀN) ✅ + a11y content-desc (turn/đường/ETA, **foreground only**) + camera (chỉ trên map SurfaceView → cần B3 screen-capture).
- **Waze/WazeMod**: KHÔNG có kênh data nền; nav = **screen-capture mũi tên (B3, học OpenBYD)** hoặc WazeMod HLP-logcat (cần WazeMod cấu hình) hoặc ESP32 HUD.
