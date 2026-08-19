# Factory (ZIN) HUD nav — tổng hợp đường RE + xếp hạng khả thi

> **Loại:** Diagnostics · **Trạng thái:** Current · **Ngày:** 2026-08-19
> **Mục đích:** Gom MỌI đường tìm được (5 track RE) để làm **HUD kính ZIN (OEM/nhà máy)** hiện nav, phân loại khả thi × chi phí, kèm bằng chứng `file:line` và điều-kiện-mở-khoá cho từng đường "không thể" (theo `trace-den-tan-cung.md`).
> **Nguồn:** tổng hợp `docs/_handoff/re-hud-track{1..5}-*.md` (RE đọc-hiểu 2026-08-19) + `docs/diagnostics/hud-provisioning-compare-2026-08-19.md` + `docs/decisions/0002-hud-nav-coding-locked.md`.
> **Scope:** RE đọc-hiểu; doc này KHÔNG sửa code, KHÔNG commit/push. Việc coding XE (OBD/UDS) là quyết định của owner.

---

## 0. Đính chính TRỌNG TÂM — HUD Taobao ≠ HUD zin (đọc trước tất cả)

Kết luận trước đây (ADR 0002 amend + `hud-provisioning-compare-2026-08-19.md` + 3/5 track) cho rằng **`0x38B00030` đã bị BÁC là gate**, vì "xe anh em đọc `0x38B00030 = −2147482648` y hệt owner mà HUD vẫn hiện nav". **Suy luận đó SAI ở chỗ so sánh nhầm đối tượng:**

- **HUD hiện nav trên xe anh em là HUD Taobao aftermarket** — một thiết bị gắn thêm, **đường render ĐỘC LẬP**, đọc tín hiệu nav từ bus (guide-info app phát ra) rồi **tự vẽ bằng firmware riêng của nó**. Nó **KHÔNG đi qua firmware/MCU render của HUD zin**.
- Vì vậy "xe anh em lên nav" chỉ **chứng minh app ghi tín hiệu nav lên bus ĐÚNG** (Taobao HUD bắt được + vẽ; **cụm cũng hiện nav**). Nó **KHÔNG chứng minh** lớp nav của **HUD zin** có bị gate bởi `0x38B00030` hay không — vì hai đường render khác nhau hoàn toàn.
- ⇒ **`0x38B00030` VẪN là nghi phạm gate HUD-ZIN** (consumer `Hud00600401300000.readSelfLearnState()==1`, RE libBydCluster/carsettings). **CHƯA bị bác.** Việc hai xe cùng đọc `−2147482648` không nói lên gì về HUD zin, vì xe anh em không dùng HUD zin để hiện nav.
- **Biến thể `40d` 138 (owner) vs 162 (anh em) chỉ là khác biệt TÌNH CỜ bắt được** — khác biệt THẬT giữa hai quan sát là **HUD zin (owner) vs HUD Taobao (anh em)**, không phải con số `40d`. Không có bằng chứng nào cho thấy re-code `138→162` sẽ bật HUD-zin nav; đó là suy đoán chưa có cơ sở.

**Hệ quả:** hiện **CHƯA có ví dụ nào xác nhận một HUD zin hiện nav** (mẫu "162 lên nav" là HUD Taobao, không tính cho HUD zin). Câu hỏi RE mở lại về đúng trọng tâm: **lớp nav của HUD zin bị gate bởi cờ provisioning firmware nào — `0x38B00030` (± cờ họ hàng) là nghi phạm số 1, chưa bác.**

### Hai đường ra (owner quyết)

| | Đường | Trạng thái | Bản chất |
|---|---|---|---|
| **(a)** | **HUD Taobao aftermarket** | **ĐÃ CHỨNG MINH chạy** với app hiện tại | Render độc lập; app ghi guide-info đúng → Taobao HUD tự vẽ. Chi phí = mua thiết bị (~vài trăm k–vài triệu). Không đụng firmware zin. |
| **(b)** | **HUD zin (provisioning firmware)** | **CHƯA mở** — nghi phạm `0x38B00030` ± cờ khác | Cần crack/coding lớp provisioning firmware. Các đường xem bảng xếp hạng bên dưới. |

---

## 1. Bảng xếp hạng đường (khả thi × chi phí)

> Xếp từ **NÊN LÀM TRƯỚC** (khả thi cao × chi phí thấp / info-gain cao) → **bất khả thi** (kèm điều-kiện-mở-khoá). "Khả thi" = xác suất mở được HUD-zin nav (hoặc thu được bằng chứng quyết định). "Chi phí" = công + rủi ro + phụ thuộc tool/hardware ngoài.

| # | Đường | Phân loại | Khả thi | Chi phí | Bước kế (1 dòng) |
|---|-------|-----------|---------|---------|------------------|
| **0** | **HUD Taobao aftermarket** (đường render độc lập) | ✅ đã chứng minh | **CAO** (đã chạy) | Thấp–TB (mua thiết bị) | Nếu owner chấp nhận HUD gắn thêm → mua 1 chiếc, app phát guide-info sẵn rồi |
| **1** | Sweep 2 **fusion switch** `0x8e2fcdbf`/`0xd61b6746` + đọc feedback `0x38B00034`/`0x38B00032` | 🔬 on-car probe (non-root) | Thấp–TB | **Rất thấp** | On-car: set 2 switch = 1, kèm guide feed, xem HUD zin đổi gì; đọc feedback |
| **2** | **ICarHudService** đọc capability HUD zin owner (`getHudConfig(NAVIGATION_MAP/FUSION/DYNAMIC)`, `getHudSupportedModes`, `isNavigationMapEnabled`) | 🔬 on-car probe (đọc, cần DiCar client đặc quyền) | Thấp (chẩn đoán) | Thấp | On-car: navopen-style đọc capability → HUD zin có "quảng cáo" nav support không |
| **3** | Probe `0x32B1102E` (INSTRUMENT_HUD_NAVIGATION_MAP_SET) đọc→thử set 2→rollback | 🔬 on-car probe (non-root) | Thấp | Rất thấp | On-car: `getraw instr 32B1102E`, thử `setraw 2`, đọc lại, rollback |
| **4** | **Provision `0x38B00030=1`** (INSTRUMENT_HUD_NAVIGATION_MAP_CONFIG) — nghi phạm gate chính | 🔧 cần tool coding (UDS/OBD, seed-key) | **TB–CAO** (nếu đúng gate) | CAO (tool + rủi ro DTC) | Dealer/OBD-UDS `WriteDataByIdentifier` set config nav-map; HOẶC on-car `BYDAutoOtaDevice 0xAA000140` (gated seed/key) |
| **5** | **Variant-coding dump** đầy đủ HUD zin owner → tìm cờ provisioning nav-map cụ thể | 🔧 cần tool coding (đọc coding) | TB (chẩn đoán) | CAO (tool ngoài) | Dump coding instrument-ECU; tra cờ họ HUD-nav (`0x38B00030` + lân cận) |
| 6 | **Provision qua CAN sniff** trên một xe có HUD-zin-nav thật (nếu tìm được) → diff frame | 🔬 on-car probe (cần xe mẫu) | Thấp (phụ thuộc tìm xe) | TB–CAO | Cần 1 xe HUD **zin** thật lên nav để sniff `BYDAutoBigDataDevice` → so bus |
| 7 | HAL write thẳng `0x38B00030=1` | ❌ bất khả thi qua app | 0 | — | **Rejected on-car**; đọc-only. Mở khoá = UDS coding (đường 4) |
| 8 | Nhồi nav vào field HUD đang render (ADAS/tốc-độ/call) | ❌ bất khả thi | 0 | — | QML bind 1 chiều + HUD zin **thiếu widget nav**. Mở khoá = provision widget (đường 4/5) |
| 9 | CAN-inject frame để **bật** nav-HUD-mirror | ❌ bất khả thi cho nav | 0 | — | Là softcode MCU, không frame app nào lật. Mở khoá = UDS coding |
| 10 | App kỹ thuật/factory Android set coding HUD (BydDevelopmentTools) | ❌ bất khả thi | 0 | — | Không app nào trong image làm coding. Mở khoá = tool BYD ngoài |
| 11 | settings-secure / persist prop / service-call firmware đọc để bật | ❌ bất khả thi | 0 | — | Không surface Android-side nào firmware tra. Gate ở MCU |
| 12 | Gọi cross-process `VehicleSettings.setHudNavigationState(true)` của app OEM | ❌ bất khả thi qua app | 0 | — | Setter Ui→Mcu, permission-gated + vẫn bọc bởi provisioning MCU |

**Tóm tắt chiến lược:** làm **đường 1→2→3** trước (rẻ, on-car, non-root, thu thông tin về HUD zin owner) → nếu cả 3 xác nhận HUD zin **không quảng cáo/không nhận** nav ⇒ củng cố giả thuyết provisioning `0x38B00030`, và **đường 4** (UDS coding) là cửa mở khoá thật. **Đường 0** (Taobao) là fallback **đã chạy** nếu owner chỉ cần "có nav trên kính" mà không nhất thiết là HUD zin.

---

## 2. Chi tiết từng đường

### Đường 0 — HUD Taobao aftermarket (ĐÃ CHỨNG MINH) ✅

- **Mô tả:** Một HUD gắn thêm mua Taobao, đọc tín hiệu nav từ bus và **tự render** bằng firmware riêng — không phụ thuộc provisioning của xe. Đây là đường **duy nhất đã xác nhận hiện nav** với app hiện tại.
- **Bằng chứng:** `hud-provisioning-compare-2026-08-19.md §1–2` — xe anh em (HUD Taobao) hiện nav bằng **chính app này + GMaps**; trước khi cài app HUD không có nav; log `AmapService` ghi `0x43E0003A=2`, `0x43FA1008` (pathname), `0x43F02018/0201E` (trip) **rc=0, 0 reject**. ADR 0002 §Context: "cùng đời HUD (**mua Taobao**) gắn trên một xe BYD Sealion 6". App ghi đúng: `[track4 Phụ lục B; BydHal.kt:284/290/312/313]`.
- **Phân loại:** ✅ **off-car RE xong + đã chạy on-car** (trên thiết bị Taobao). App-side hoàn tất.
- **Rủi ro:** thấp — thiết bị gắn thêm, không đụng firmware xe. Chất lượng render phụ thuộc HUD Taobao. Không phải "HUD zin" (nếu owner yêu cầu đúng HUD zin thì đây không thoả).
- **Bước kế:** nếu owner chấp nhận HUD gắn thêm → mua 1 chiếc tương thích; app đã phát guide-info sẵn, không cần đổi code. Xác minh loại HUD + giao thức bus mà thiết bị Taobao dùng (CAN trực tiếp / OBD).

### Đường 1 — Sweep 2 fusion switch `0x8e2fcdbf` / `0xd61b6746` 🔬

- **Mô tả:** `SET_NAVIGATION_FUSION_SWITCH_SET (0x8e2fcdbf)` + `SET_SAFETY_DRIVING_AID_FUSION_SWITCH_SET (0xd61b6746)` là 2 switch **ghi-được** ở SettingDevice, có thể chi phối việc "fuse" nav lên HUD/cụm — **chưa sweep dứt điểm** (§19 mới thử HUD mode/switch + guidance, chưa thử 2 fusion switch obfuscated này kèm guide feed).
- **Bằng chứng:** `[track4 Q1.3 bảng SettingDevice]` — `0x8e2fcdbf` NAVIGATION_FUSION (feedback `0x38B00034`), `0xd61b6746` SAFETY_DRIVING_AID_FUSION (feedback `0x38B00032`); `[track4 Q4.2 cửa (5)]` liệt kê là "chưa sweep dứt điểm — rẻ, đáng xác nhận".
- **Phân loại:** 🔬 **on-car probe (non-root, app-reachable)**.
- **Rủi ro:** thấp — 2 switch cùng họ HUD/nav SettingDevice; ghi thử + đọc feedback + rollback. Rủi ro làm "làn cụm nhảy sang HUD" đã được cảnh báo trong `CarExecClusterDiagnosticsCatalog.kt` → theo dõi cụm khi test.
- **Bước kế:** on-car set mỗi switch = 1 (kèm guide feed 43E/43F đang chạy), quan sát HUD zin + đọc feedback `0x38B00034`/`0x38B00032`; rollback về giá trị cũ.

### Đường 2 — ICarHudService đọc capability HUD zin owner 🔬

- **Mô tả:** `ICarHudService`/`ICarHudManager` (DiCar vision SPI) có các **read capability**: `getHudSupportedModes()`, `getHudConfig(featureMask)`, `isNavigationMapEnabled()`. Đọc để biết **HUD zin owner có "quảng cáo" nav support** (bit `NAVIGATION_MAP=2048`/`NAVIGATION_FUSION=1024`/`DYNAMIC_NAVIGATION=256`) hay không — chẩn đoán quyết định.
- **Bằng chứng:** `[track1 §1; HudFeature.java:6-20]` bit mask; `[track1 §1; ICarHudManager.java:12,14,20,24,26]` getHudConfig/getHudSupportedModes/is*Enabled; `[track1 §2; car/n2.java:26-30]` transport qua ContentProvider `CarServiceProvider/sync_binder`; `[track1 §2; PermissionUtils.java:11-31]` server gate theo caller uid. **Không có content-push method** (không setTurnIcon/distance) → chỉ đọc/enable, không tự vẽ nav.
- **Phân loại:** 🔬 **on-car probe (đọc-only)** — cần **DiCar client đặc quyền** (navopen-style app_process). Ghi = ❌ (permission-gated system/OEM-signed; dadb uid-2000 thiếu quyền BYD).
- **Rủi ro:** thấp (chỉ đọc). Nếu provider chặn export/permission → không đọc được (ghi nhận là "không tiếp cận").
- **Bước kế:** on-car `dumpsys package providers | grep CarServiceProvider` (đọc exported/permission), rồi navopen đọc `getHudConfig(NAVIGATION_MAP)` + `getHudSupportedModes()`. **Không cần ghi.** Kết quả "HUD zin không advertise nav" ⇒ củng cố giả thuyết provisioning.

### Đường 3 — Probe `0x32B1102E` (HUD_NAV_MAP_SET) 🔬

- **Mô tả:** `0x32B1102E` (INSTRUMENT_HUD_NAVIGATION_MAP_SET, 2=ON/1=OFF) là **setter runtime** gần nghĩa "bật nav-map HUD" nhất; OEM `Hud00600401300000.setState()` ghi id này. Đọc trước, thử set 2, đọc lại, rollback — xác nhận nó có phải unlock (kỳ vọng thấp: bị reject khi config chưa provision).
- **Bằng chứng:** `[track2 §Consumer; Hud00600401300000.setState → DiCarSetter.set("0x32B1102E", 2/1)]`; `[track2 Instrument.java:538]`; `[track3 (2); track4 Q1.2]` "chỉ có `0x32B1102E` ghi được nhưng REJECTED vì `0x38B00030` chưa provisioned".
- **Phân loại:** 🔬 **on-car probe (non-root)** — kỳ vọng thấp (đã reject khi unprovisioned) nhưng rẻ, đáng đóng cửa.
- **Rủi ro:** thấp — 1 setter runtime, có rollback. Nếu rc=0 mà HUD vẫn không nav ⇒ xác nhận thêm gate là provisioning (config), không phải toggle.
- **Bước kế:** on-car `getraw instr 32B1102E` → `setraw instr 32B1102E 2` → đọc lại + quan sát HUD zin → rollback giá trị cũ.

### Đường 4 — Provision `0x38B00030=1` qua UDS coding (NGHI PHẠM GATE CHÍNH) 🔧

- **Mô tả:** `0x38B00030` (INSTRUMENT_HUD_NAVIGATION_MAP_CONFIG) là **cờ provisioning/capability** mà consumer `Hud00600401300000.readSelfLearnState()` kiểm tra `== 1` — cơ chế "cụm mirror nav → HUD zin". **Đây là nghi phạm gate HUD-zin số 1, CHƯA bị bác** (đính chính §0). Set nó =1 = provisioning firmware, chỉ làm được qua UDS coding.
- **Bằng chứng:** `[track2 §Consumer; Hud00600401300000.readSelfLearnState() = get("0x38B00030")==1]`; `[track2 Instrument.java:536]`; `[track4 Q4.2 cửa (4); BYDAutoOtaDevice set({0xAA000140}, udsFrame) + listener {0x99000140}, đọc softcode getbytes ota 99000053]`; `[track3 Verdict; UDS DiagnosticSessionControl(0x10)+SecurityAccess(0x27)+WriteDataByIdentifier(0x2E)]`. Giá trị BẬT = `1`; `−2147482648` = sentinel not-provisioned `[track2 (3)]`.
- **Phân loại:** 🔧 **cần tool coding (UDS/OBD, seed-key gated)** — HOẶC on-car `BYDAutoOtaDevice` (cũng gated coding DID + security-access 0x27).
- **Rủi ro:** **CAO** — coding sai → DTC / misconfig instrument-ECU; cần recovery. Security-access seed/key có thể chặn hoàn toàn nếu không có key dealer.
- **Bước kế:** (owner quyết) mang xe tới nơi có tool coding BYD; đọc coding hiện tại họ HUD-nav; thử set `0x38B00030`/config nav-map = provisioned; test app guide feed (đã chạy sẵn) có lên HUD zin không. **Cần xác định coding DID cụ thể** (đường 5).

### Đường 5 — Variant-coding dump HUD zin owner → tìm cờ provisioning cụ thể 🔧

- **Mô tả:** Dump toàn bộ variant coding của instrument/HUD ECU xe owner để tìm **cờ/DID provisioning nav-map cụ thể** (họ `0x38B00030` + lân cận), thay vì đoán. Đây là bước chẩn đoán nền cho đường 4.
- **Bằng chứng:** `[track3 (3); CarInfo.java 138=EK/0x8A, 162=SA3EJ/0xA2]` — `40d` đọc-only qua `ICarInfoManager` (10 getter, 0 setter); `[track2 (4)]` "coding softcode MCU, đọc qua HAL, set qua UDS ngoài"; `[track3 Verdict]` "cờ coding cụ thể chưa xác định — cần variant-coding dump". Lưu ý §0: **không nên đóng khung là 138→162** (khác biệt tình cờ); nên tìm **cờ họ HUD-nav provisioning** trực tiếp.
- **Phân loại:** 🔧 **cần tool coding (đọc coding)**.
- **Rủi ro:** TB — chỉ đọc coding (không ghi) thì an toàn; cần tool + có thể cần security-access để đọc coding block.
- **Bước kế:** dùng tool chẩn đoán BYD đọc coding instrument-ECU; đối chiếu họ cờ HUD-nav (`0x38B00030`, `0x30100030`, `0x38B0002E`) với giá trị "provisioned" mong đợi.

### Đường 6 — CAN sniff trên một xe có HUD **zin** nav thật (nếu tìm được) 🔬

- **Mô tả:** Nếu tìm được **một xe có HUD ZIN (không phải Taobao) thật sự hiện nav**, sniff bus lúc HUD zin render nav để so với xe owner → xác định frame/coding khác biệt. `BYDAutoBigDataDevice` cho sniff whole-frame on-device không cần hardware.
- **Bằng chứng:** `[track4 Q3.2; CanDataCollectService.java:375-377 registerListener({-1728053216}), :290 onWholeFrameDataChanged(byte[])]`; `[track4 CanDataHandle.java:232-247 format frame]`. **Lưu ý:** đây là công cụ; **điều kiện tiên quyết là tìm được xe HUD-zin-nav thật** — hiện **chưa có** (mẫu anh em là Taobao).
- **Phân loại:** 🔬 **on-car probe** nhưng **phụ thuộc tìm xe mẫu HUD-zin-nav** (chưa có).
- **Rủi ro:** TB — sniff đọc-only an toàn; rủi ro là **không tìm được xe mẫu** ⇒ đường này bế tắc cho tới khi có mẫu.
- **Bước kế:** tìm xe BYD (trim/region) mà **HUD zin** hiện nav; nếu có → sniff + diff. Nếu không tìm được → không khả dụng.

### Đường 7 — HAL write thẳng `0x38B00030=1` ❌

- **Phân loại:** ❌ **bất khả thi qua app/adb.**
- **Bằng chứng:** `[track2 (5); findings-2026-08-10 §10]` write `0x38B00030` **REJECTED** on-car; `[track4 Q1.2]` họ `0x38B0xxxx` là feedback/config **đọc-only, không có `*_SET`**; `[track3 (2)]` "ghi thẳng `0x38B00030` đã bị REJECT".
- **Điều-kiện-mở-khoá:** set qua **UDS coding ngoài** (đường 4), không phải HAL write từ head-unit.

### Đường 8 — Nhồi nav vào field HUD đang render (ADAS/tốc-độ/call) ❌

- **Phân loại:** ❌ **bất khả thi trên trim này.**
- **Bằng chứng:** `[track4 Q2]` HUD zin trim này có widget km/h + speed-limit + ADAS nhưng **KHÔNG có widget nav** (owner xác nhận §19); QML bind **một chiều** `Text{text:DataSource.x}` (§25 Q4), giá trị do data-item CAN nội bộ nuôi, **QML không gán ngược**; ghi guidance `0x43F01010`/SDK `sendSimpleGuidanceInfo` → **rc=0 nhưng HUD trống**; repurpose field call phá UI cuộc gọi, không phải nav thật.
- **Điều-kiện-mở-khoá:** provision để **widget nav xuất hiện** (= coding `0x38B00030` family, đường 4/5). Không có widget thì không có gì để "nhồi".

### Đường 9 — CAN-inject frame để bật nav-HUD-mirror ❌

- **Phân loại:** ❌ **bất khả thi cho việc BẬT nav-HUD** (chỉ khả thi để giả **giá trị** speed-limit, không phải nav).
- **Bằng chứng:** `[track4 Q3.3]` nav-HUD-mirror là **softcode MCU `0x38B00030`** (coding UDS), **không frame CAN nào app phát ra lật được**; `[track4 Q3.4]` CanDataCollect = telemetry thuần (0 ref hud/nav); `BYDAutoTestDevice 0xAA00020F` inject chỉ giả tín hiệu speed-limit **value**, không tạo widget nav.
- **Điều-kiện-mở-khoá:** UDS coding (đường 4). ESP32/CAN-inject không tạo được lớp nav trên HUD zin.

### Đường 10 — App kỹ thuật/factory Android set coding HUD ❌

- **Phân loại:** ❌ **bất khả thi — không tồn tại trong image.**
- **Bằng chứng:** `[track3 (1)]` `BydDevelopmentTools` = repair/rollbench/OBD-readiness/log; grep `hud|coding|variant|writeData|0x2E|38B000|4C10E0` trên app-code = **0 khớp**; `[track3 (2)]` grep UDS primitive `WriteDataByIdentifier|SecurityAccess|DiagnosticSession` toàn corpus = **0 khớp**; `vehiclesettings` HUD UI chỉ **đọc** `0x38B00015`, ghi user-toggle, không code.
- **Điều-kiện-mở-khoá:** tool coding BYD ngoài (dealer/OBD), không có app Android nào thay được.

### Đường 11 — settings-secure / persist prop / service-call firmware đọc ❌

- **Phân loại:** ❌ **bất khả thi — không có surface Android-side nào.**
- **Bằng chứng:** `[track3 (4)]` persist prop duy nhất là `repair_mode` (không liên quan); không Settings.Secure/Global key nào firmware tra để bật HUD-nav; quyết định ở MCU variant coding, không phải giá trị Android.
- **Điều-kiện-mở-khoá:** provisioning MCU (đường 4/5).

### Đường 12 — Cross-process `VehicleSettings.setHudNavigationState(true)` ❌

- **Phân loại:** ❌ **bất khả thi qua app.**
- **Bằng chứng:** `[track5 (4) bề mặt chưa thử; track3 (1)]` `HudOptionDisplayModel.setHudNavigationState` là setter **Ui→Mcu của app OEM**, permission-gated, **vẫn bọc bởi provisioning MCU**; app mình không gọi cross-process được (thiếu quyền).
- **Điều-kiện-mở-khoá:** kể cả gọi được cũng vẫn cần provisioning MCU (đường 4). Không phải đường tắt.

---

## 3. Điều còn mở (trace-den-tan-cung — chưa đóng cửa)

1. **HUD zin owner có "advertise" nav support không** — chưa đọc `ICarHudService.getHudConfig/getHudSupportedModes` on-car (đường 2). Chưa làm ⇒ chưa kết luận HUD zin thiếu widget nav ở tầng capability.
2. **Cờ provisioning HUD-zin-nav cụ thể** — `0x38B00030` là nghi phạm số 1 (source-level `readSelfLearnState==1`), **chưa xác nhận** là đủ để bật (chưa từng set =1 thành công để test). Có thể cần thêm cờ họ hàng (`0x30100030` config-status, `0x38B0002E` status). Cần: đọc coding dump (đường 5) + thử set (đường 4).
3. **Chưa có mẫu HUD ZIN nào hiện nav** để đối chứng (mẫu anh em là Taobao). Nếu tìm được xe HUD-zin-nav thật ⇒ mở đường 6 (sniff + diff coding).
4. **`40d` 138 vs 162 là khác biệt tình cờ**, chưa có cơ sở nói re-code sang 162 sẽ bật HUD-zin nav. Không đóng khung unlock theo con số này; đóng khung theo **cờ provisioning nav-map**.

## 4. Nguồn (handoff + file:line)

- **Track 1** (`docs/_handoff/re-hud-track1-hudservice.md`): `HudFeature.java:6-20` (bit mask nav), `ICarHudManager.java:12/14/20/24/26/38/39/44/45/46/47`, `car/n2.java:26-30` (ContentProvider transport), `PermissionUtils.java:11-31` (server permission gate), `Hud00600401300000` getState/setState/readSelfLearnState.
- **Track 2** (`docs/_handoff/re-hud-track2-firmware-gate.md`): `Instrument.java:536` (`0x38B00030`), `:537` (`0x30100030`), `:538` (`0x32B1102E`), `:539` (`0x38B0002E`), `:515` (`0x43F01010`), `:762` (`0x43E0003A`); `Hud00600401300000.java` readSelfLearnState(`==1`)/getState(`==2`)/setState(2/1).
- **Track 3** (`docs/_handoff/re-hud-track3-coding-path.md`): `BydDevelopmentTools` surface (repair/rollbench/log), grep âm tính UDS + hud/coding, `CarInfo.java` (138=EK/0x8A, 162=SA3EJ/0xA2), `ICarInfoManager` (10 getter/0 setter), `Setting.java` bảng SET_HUD_*, verdict UDS ngoài (0x10/0x27/0x2E).
- **Track 4** (`docs/_handoff/re-hud-track4-hal-can.md`): `Setting.java` SET ids + 2 fusion switch `0x8e2fcdbf`/`0xd61b6746` (feedback `0x38B00034`/`0x38B00032`), `Instrument.java` họ `0x38B*` đọc-only, `Adas.java` SLA read-only, `CanDataCollectService.java:375-377/:290`, `CanDataHandle.java:232-247`, `BydHal.kt:284/290/312/313`, Q2 (QML bind 1 chiều, không widget nav), Q4 5 cửa chưa thử.
- **Track 5** (`docs/_handoff/re-hud-track5-priorart.md`): `windshield-hud-enable.html` (plan, on-car NOT STARTED, Q1–Q10 mở), findings-2026-08-10 (14 mục đã thử & fail), OpenBYD `CarControlImpl` (xác nhận API write đúng: `0x43F01010`/`0x4C10E015`), `ICarHudService` bề mặt chưa thử (đọc-only), `HudOptionDisplayModel.setHudNavigationState` (Ui→Mcu, gated), AR-HUD tier `0x34C0000B`.
- **Diagnostics nền:** `docs/diagnostics/hud-provisioning-compare-2026-08-19.md` (HUD Taobao anh em — **đính chính §0**: là aftermarket, render độc lập, KHÔNG bác được `0x38B00030` cho HUD zin), `docs/decisions/0002-hud-nav-coding-locked.md`.

> RE-only. Không sửa code, không commit/push.

---

## Cập nhật 2026-08-19 (chiều) — khe "TÊN ĐƯỜNG không lên HUD" (RE tập trung)

**Bối cảnh mới từ owner:** HUD "Taobao" thực chất là **HUD BYD xịn** (VN cắt ra, anh em mua gắn lại) → nói đúng protocol OEM. Qua **app ClusterNav**: HUD xe anh em lên **mũi tên + cự ly**, **CHƯA lên tên đường**. Showroom mode (OEM) thì hiện đủ cả tên đường (五一大道南).

**Phát hiện (evidence decompile):**
- Tên đường `0x43FA1008` (TARGET_NEXT_PATHNAME) là **buffer, ghép cặp với `0x420A1010` GET_ROAD_NAME_CHECK_STATE** (SDK `getRoadNameCheckState()` → VALID=1/INVALID=2). Mũi tên `0x43F01010` + cự ly là **INT, KHÔNG có check-state** → vẽ vô điều kiện. **Đây là lý do bất đối xứng** (mũi tên/cự ly lên, tên đường không).
- **App CHƯA BAO GIỜ đọc `0x420A1010`** (grep core/app = 0) → mù việc MCU coi chuỗi VALID hay không.
- App ghi **khác format OEM**: OEM ghi khung cuộn có dấu-cách dẫn `[32,0,'K',...]` + chữ CJK; app ghi chuỗi thô 1 lần, có thể chứa dấu tiếng Việt (font cụm chưa chứng minh phủ).
- **Showroom KHÔNG có "chuỗi bí mật" trong Android** — demo nav do **firmware MCU cụm tự vẽ** (không qua SDK). Xác nhận MCU có widget nav + ô tên đường, nhưng không copy được đường Android.

**Test rẻ (đã thêm vào `scripts/vehicle/hud-nav-enable-probe.sh` PHASE A + D):** đọc `0x420A1010` lúc app đang dẫn:
- **`2` INVALID** → MCU từ chối chuỗi tên đường của app → thử **charset/khung**: dấu-cách dẫn, thêm NUL UTF-16LE, bỏ dấu tiếng Việt (ASCII), giữ ≤7-8 ký tự.
- **`1` VALID** mà kính vẫn trống → **widget/coding gate `0x38B00030`** (không phải format).

**Trung thực:** register/cặp/encoding = bằng chứng cứng từ decompile. Việc INVALID có THỰC SỰ chặn render hay không nằm ở **firmware MCU cụm** (ngoài nguồn Android) → **test đọc `0x420A1010` on-car là mấu chốt, CHƯA chạy**. Đây là khe tractable nhất hiện có cho "nav đầy đủ trên HUD BYD"; xe owner (HUD trắng hoàn toàn) vẫn là gate `0x38B00030` riêng.

---

## Cập nhật 2026-08-19 (chiều-2) — ĐƯỜNG CODING/UDS cho HUD zin xe owner (RE sâu)

**ECU:** `0x38B00030` + họ (`30100030` CONFIG_STATUS, `32B1102E` SET, `38B0002E` STATUS, `34C00026` FORMAT) do **cụm đồng hồ (instrument-cluster) giữ** (`Instrument.java:534-539` → `InstrumentMapper` → `BYDAutoInstrumentDevice`; render + config store trong cụm, `libBydDataSource.so`). `-2147482648` (=`0x800003E8`) là **giá trị THẬT cụm trả**, không phải lỗi parse.

**Cơ chế provisioning (phát hiện mới, có cơ sở):** `BusinessSelfStudy::vehicleCodeSelfStudyUpdate` + log *"selfstudy has completed, vehicleCode is 0x%02x"* → `BydConfigInfo`/`ConfigureManager` (config XML), **cùng lớp equipment với ADAS**. **`40d` 138=`0x8A` / 162=`0xA2`** khớp khuôn `vehicleCode` 1-byte → HUD-nav là **feature bật bằng coding equipment/vehicleCode của cụm**. (KHÔNG có bảng `0xA2→ON` trong image → giá trị bật cụ thể chưa biết.)

**On-device coding — CÓ kênh nhưng KHÓA:** tồn tại UDS-thô on-device (`BYDAutoOtaDevice 0xAA000140` + `BYDAutoSettingDevice` secret-OBD `0xAA000241/0x99000241`); factory app `BydDevelopmentTools` chạy UDS chuẩn qua đó (`10 03`→`27 01/02`→`2E/31`). **Nhưng gated `sharedUserId="android.uid.system"` (platform-signed) + per-property permission.** ClusterNav (user-signed / dadb uid-2000) **KHÔNG với tới** — cần root/platform-key HOẶC drive BydDevelopmentTools. Factory tool **không có màn coding HUD** (chỉ CAN-ID mapping + đọc version).

**OBD-UDS ngoài:** `10 03` + SecurityAccess `27 01/02` + `2E/31`. Thuật toán seed→key **lộ** trong `ObdDataManager.k()` — **nhưng chỉ cho GATEWAY (`0x720/0x747`), KHÔNG phải cụm.** DID coding nav-HUD **không có trong image**; key + DID của **cụm** chưa biết.

**Kết luận cho xe owner (thành thật):** bật HUD-nav = **việc CODING cụm** (equipment/vehicleCode self-study), **KHÔNG sửa được bằng app/script** (kênh on-device khoá sau `android.uid.system`; app không có quyền). Đường thực tế:
1. **Tool coding BYD dealer** (OBD-II + ODX): đọc equipment matrix cụm → bật cờ HUD-nav (key dealer) → trigger self-study → `0x38B00030=1` → test app. ⟵ chắc nhất.
2. **On-device** chỉ khả thi nếu có **root/platform-key** + reqId/rxId + security-key + DID của **cụm** (đều chưa biết) → rủi ro cao.
- **Bước AN TOÀN đọc-only kế (on-car):** `getraw instr 30100030` (CONFIG_STATUS) + `38B0002E` (STATUS) + thử UDS `22 <DID>` đọc — không ghi.

**Điều kiện mở khoá (để owner quyết):** cần **máy chẩn đoán/coding BYD + DB equipment cụm** (giá trị vehicleCode/flag bật HUD-nav). Đây là việc **coding XE tại tiệm có tool**, không phải app.
