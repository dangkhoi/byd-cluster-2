# Cụm-centre "Giữa + ETA" KHÔNG render — ROOT CAUSE (RE) + code chuẩn

> **Trạng thái**: Current · **Ngày**: 2026-08-21 · **Loại**: Diagnostics (finding có bằng chứng)
> **Mục đích 1 dòng**: Vì sao ghi HAL guide rc=0 mà cụm-centre trống, và đường render ĐÚNG cho cụm fission (từ RE OEM AmapService).

## TL;DR
Cụm xe là loại **fission / "1 for 2"** (display 1 = 1920×720, `com.xdja.containerservice`). Trên loại này, OEM **KHÔNG** render centre từ CAN/HAL guide registers — nó render từ một **NaviInfo flatbuffer đẩy qua `AutoContainerManager.sendInfo2(4, bytes)`**. App (và navopen sáng nay) chỉ ghi HAL guide (0x43E/0x43F) + raise overlay rỗng (op39) ⇒ **rc=0 nhưng trống trơn**. Đường flatbuffer app **chưa từng có**.

## 1. Bằng chứng sáng nay (fail)
Commit `8121e2a` (08-21): navopen OPEN nav + full frame → mọi register content **rc=0** (`0x43E0003A=2`, `0x4C10E015=3`, guide/dist/pathname/eta) NHƯNG **KHÔNG render** ở cụm-centre lẫn HUD (cụm blank, screencap 12.8KB, không bị cast chiếm). op39 + AUTONAVI broadcast **không được test** — nên "recipe broadcast+op39" nêu trước đó là **giả thuyết chưa kiểm chứng**, không phải cách đã proven.

## 2. Root cause — RE OEM `AmapService` (đường render thật)
Nguồn: `~/Library/Caches/clusternav-re/decoded/f3147928…/jadx-auto/sources/`
- `com/example/amapservice/AmapService.java` (835 dòng — service OEM tích hợp AMAP→cụm)
- `android/os/AutoContainerManager.java`
- `byd/fbs/naviInfo/NaviInfo.java` (flatbuffer)

### 2.1 Rẽ nhánh theo loại cụm
```java
// AmapService onCreate
mClusterType = SystemProperties.get("ro.build.system.fission_single_os");  // dòng 138
...
// sendNaviToCluster(delay)  — dòng 435
if (!mClusterType.equals("1")) {
    sendNaviInfoTo1for2Clster();   // ← FISSION: NaviInfo flatbuffer qua AutoContainer
    sendNavigateInfoToCAN();       //   + CAN (phụ)
} else {
    sendNavigateInfoToCAN();       // ← SINGLE-OS: chỉ CAN/HAL guide registers
}
```
- **`ro.build.system.fission_single_os == "1"`** → cụm đọc **CAN/HAL guide** (đúng thứ app đang ghi).
- **`!= "1"`** (fission/dual-OS) → cụm-centre đọc **AutoContainer flatbuffer** (`sendInfo2(4, …)`). CAN chỉ là phụ.
- Cụm xe = **fission virtual** (`com.xdja.containerservice`, display 1 1920×720) ⇒ **gần như chắc chắn `!= "1"`** ⇒ centre KHÔNG lấy từ HAL guide. Đây là lý do ghi HAL rc=0 mà trống.

### 2.2 Content push thật (đoạn app THIẾU)
```java
// AmapService.sendNaviInfoTo1for2Clster()  — dòng 724
builder.clear();
builder.finish(NaviInfo.createNaviInfo(builder,
    naviState, nextRouteName, curToSegmentDist, forwardState, nextTurnIcon,
    routeRemainTime, routeRemainDist, stringEtaArrivalTime, exitNameInfo,
    exitDirectionInfo, routrRemainDisAuto, routrRemainTimeAuto, SegRemainDisAuto,
    nextNextTurnIcon, nextToSegmentDist, nextNextRouteName, roungAboutNum, nextRoungAboutNum));
byte[] bytes = builder.sizedByteArray();
autoContainerManager.sendInfo2(4, bytes);   // ← CHANNEL 4, flatbuffer = CONTENT centre
```
`autoContainerManager = getSystemService("auto_container")` (fallback `"AutoContainer"`).

### 2.3 op39 làm gì (và vì sao chưa đủ)
App gọi `service call AutoContainer 2 i32 1000 i32 39 s16 ""` = `sendInfo(1000, 39, "")`. Đây chỉ **raise overlay "simple navigation"** — **KHÔNG mang content**. Không có `sendInfo2(4, flatbuffer)` theo sau ⇒ overlay rỗng ⇒ blank. **op39 ≠ content.**

### 2.4 HAL status (app đã ghi đúng, nhưng chỉ là enabler)
- `setNaviScreenStatus(0x4C10E015, 3)` = `BYDAutoSettingDevice.set` (screen mode ON).
- `setNaviStatus(INSTRUMENT_SEND_NAVI_STATUS_SET, 4)` = `BYDAutoInstrumentDevice.set` (nav status).
Hai cái này chỉ **bật chế độ**; content vẫn phải qua flatbuffer trên cụm fission.

## 3. NaviInfo flatbuffer — schema (thứ tự field từ `createNaviInfo`)
| # | field | kiểu | ghi chú |
|---|-------|------|---------|
| 1 | naviState | int | 0/1 = đang dẫn, 9 = kết thúc/reset |
| 2 | nextRouteName | string | tên đường kế |
| 3 | curToSegmentDist | int | m tới ngã rẽ |
| 4 | forwardState | string | |
| 5 | nextTurnIcon | int | **AMAP icon 0..28** (không phải HUD/CAN icon) |
| 6 | routeRemainTime | int | giây còn lại |
| 7 | routeRemainDist | int | m còn lại |
| 8 | stringEtaArrivalTime | string | giờ tới |
| 9 | exitNameInfo | string | |
| 10 | exitDirectionInfo | string | |
| 11 | routrRemainDisAuto | string | |
| 12 | routrRemainTimeAuto | string | |
| 13 | SegRemainDisAuto | string | cự ly đoạn (chuỗi "…米") |
| 14 | nextNextTurnIcon | int | |
| 15 | nextToSegmentDist | int | |
| 16 | nextNextRouteName | string | |
| 17 | roungAboutNum | int | số lối ra vòng xuyến |
| 18 | nextRoungAboutNum | int | |

## 4. Đường render ĐÚNG (cho cụm fission)
```
[nav data] → GuideInfo (18 field)
   → setNaviScreenStatus(0x4C10E015, 3)                (BYDAutoSettingDevice)   [enabler]
   → setNaviStatus(INSTRUMENT_SEND_NAVI_STATUS_SET, 4) (BYDAutoInstrumentDevice)[enabler]
   → NaviInfo flatbuffer (createNaviInfo, thứ tự §3)
   → AutoContainerManager.sendInfo2(4, bytes)          ← CONTENT (đoạn THIẾU)
   → keep-alive: lặp lại ~250-400ms (giống cụm-lane heartbeat)
```
Ghi chú reachability: `sendInfo2` nhận `byte[]` ⇒ **KHÔNG gửi được bằng `service call` CLI** (chỉ i32/s16). Phải qua **binder in-process**: `getSystemService("auto_container")` HOẶC `ServiceManager.getService("AutoContainer")` + `transact(txn, parcel{writeInt(4); writeByteArray(bytes)})`. Txn code: `sendInfo` đã biết = **2** (app dùng), `sendInfo2` **nghi = 3** (cần confirm từ Stub smali hoặc thử on-car).

## 5. Xác minh ON-CAR (chưa từng chạy — bài test dứt điểm)
1. **Xác nhận loại cụm** (1 lệnh, quyết chẩn đoán):
   ```
   adb -s <car> shell getprop ro.build.system.fission_single_os
   ```
   - `!= "1"` (rỗng/"0"/"2") ⇒ đúng như RE: centre cần flatbuffer.
   - `== "1"` ⇒ chẩn đoán sai, quay lại đường CAN/HAL (điều tra khác).
2. **Gửi 1 frame test qua flatbuffer** (probe jar §6) + `setNaviScreenStatus(3)` + `setNaviStatus(4)` → nhìn cụm-centre. Render = xác nhận đường đúng.

## 6. Bước kế
- **Probe (off-car build → on-car)**: thêm lệnh `clcentre` vào navopen (self-halting): build NaviInfo flatbuffer test + `AutoContainer.sendInfo2(4, bytes)` + 2 HAL status + keep-alive vài giây. Chạy 1 phát biết render hay không.
- **App (code chuẩn)**: thêm `com.google.flatbuffers` + class `NaviInfo` (sinh từ schema §3) + `AutoContainerCentreSink` (getSystemService/binder + sendInfo2(4,bytes)) + gate `fission_single_os != "1"` + keep-alive; owner DUY NHẤT = `NavigationHudOwner` (không đụng đường cast). Chỉ ghi khi nav-only (Cast master OFF), giống op39 hiện tại.

## 7. References (RE)
- `~/Library/Caches/clusternav-re/decoded/f3147928…/jadx-auto/sources/com/example/amapservice/AmapService.java` (dòng 138 mClusterType, 435 sendNaviToCluster, 710/717 setNaviScreen/Status, 724 sendNaviInfoTo1for2Clster, 735 createNaviInfo, 740 sendInfo2)
- `…/android/os/AutoContainerManager.java` (sendJson/sendInfo/sendInfo2)
- `~/Library/Caches/clusternav-re/diagnostic-amap/fallback/sources/byd/fbs/naviInfo/NaviInfo.java` (flatbuffer add* + field order)
- App hiện tại: `app/…/modules/clustercast/ClusterNavLaneWidget.kt` (op39 sendInfo, KHÔNG có sendInfo2/flatbuffer).
