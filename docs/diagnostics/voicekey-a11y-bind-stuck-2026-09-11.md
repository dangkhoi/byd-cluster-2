# Phím-thoại mất kết nối: AMS kẹt bind — bằng chứng đã đo & việc CÒN PHẢI ĐO trên xe · 2026-09-11

> **Trạng thái**: Current · **Cập nhật**: 2026-09-11 · **Mục đích**: Chốt lại những gì ĐÃ đo được về sự cố
> "phím-thoại mất kết nối" (và bản vá v1.40), tách rõ khỏi những gì còn là **suy luận**, kèm checklist đo
> sẵn-để-dán cho lần sau lên xe. Owner chốt 2026-09-11 17:21: *"note lại việc này để đó, khi nào lên xe xem tiếp"*.
>
> Xe: BYD Seal · DiLink 3.0 · Android 10 (API 29) · không root. Bản trên xe khi đo: **1.39 (versionCode 40)**.
> Spec bản vá: [`../specs/voicekey-a11y-bind-recovery.html`](../specs/voicekey-a11y-bind-recovery.html) (v1.40, đã ship OTA).

---

## 1. ĐÃ ĐO — trạng thái kẹt và cách duy nhất cứu được

Owner báo 15:41 phím-thoại mất kết nối. `dumpsys accessibility` trên xe:

```
Bound services:{Service[label=.custom.StatusBarAcces…]}                  ← KHÔNG có ClusterNav
Enabled services:{… com.byd.clusternav2/…NavAccessibilityService …}      ← vẫn được bật
Binding services:{{com.byd.clusternav2/…NavAccessibilityService}}         ← KẸT ở đây
ConnectionRecord{… CR FGSA DEAD com.byd.clusternav2/…NavAccessibilityService}   (×2)
ConnectionRecord{… CR FGSA DEAD com.dudu.autoui/.service.DuduAccessibilityService}
```

App **không** treo: main thread `state=S`, UI vẽ đều mỗi giây, vòng dadb của nó `shell OK: exit=0`,
`VmOverlayPos` broadcast mỗi 2 s.

Đường tự-chữa cũ (toggle setting) **đã chạy và thua**:

```
15:41:31 I/NavConnect: accessibility ENABLED nhưng CHƯA BOUND → toggle ép rebind
15:41:32 I/NavConnect: accessibility force-rebind xong: bound=false
```

Chuỗi loại trừ (mỗi bước có readback):

| # | Thử | Kết quả | Kết luận |
|---|-----|---------|----------|
| 1 | Toggle remove→re-add danh sách | `bound=false` | không cứu được |
| 2 | **Gỡ HẲN** component khỏi `enabled_accessibility_services` | `Binding services` **vẫn còn** nó | state kẹt nằm trong `system_server`, KHÔNG ở setting ⇒ mọi cách ghi setting đều vô ích |
| 3 | `am force-stop com.byd.clusternav2` | `Binding services:{}` | **chỉ tiến trình chết thì AMS mới nhả** |
| 4 | Mở lại app | `Bound services` có `ClusterNav — booster đọ…`; log `accessibility booster connected` | cứu xong |

Ghi chú phụ: mục trợ năng + notification-listener của `com.dudu.autoui` biến mất khỏi danh sách trong phiên
này — **không phải** code mình clobber: `pm list packages | grep dudu` **rỗng** (app đã bị gỡ), hệ thống tự
loại mục chết khi danh sách được ghi lại.

## 2. ĐÃ ĐO — dòng thời gian pid (đây là chỗ bác giả thuyết OTA)

| Giờ | pid | Ai gây ra |
|-----|-----|-----------|
| 13:35:20 | 22738 | tôi mở app để test |
| 13:37:42 | 24873 | tôi `am force-stop` + mở lại → sau đó **bound OK** (13:41 đọc lại: có trong `Bound services`) |
| 13:47 → 15:41 | — | **không ai đụng vào xe** |
| 15:42:17 | **3369** (`ps -o ETIME` = `01:06:40` ⇒ khởi động ~**14:35:37**) | **KHÔNG RÕ** |
| 15:44:41 | 30553 | tôi force-stop + mở lại (chữa xong) |

Hai điều rút ra, đều đi ngược suy luận ban đầu của tôi:

1. Lần chết ~**14:35** **không phải OTA** (OTA gần nhất: 2026-09-10 18:47) và **không phải do tôi**. Trạng thái
   kẹt thuộc đúng tiến trình sinh ra ở đó ⇒ **"app chết trong lúc dùng bình thường" là loại tác nhân THẬT và
   chưa giải thích được**.
2. Hôm nay tiến trình chết **nhiều lần** mà phần lớn AMS bind lại bình thường (13:37 và 15:44 đều OK) ⇒
   "chết lúc đang bound ⇒ kẹt" **không tất yếu**, chỉ là điều kiện cần. Chưa biết điều kiện cộng thêm là gì.

## 3. CHƯA ĐO / CHƯA BIẾT (đừng vá thêm khi chưa có số)

| Mã | Câu hỏi | Vì sao quan trọng |
|----|---------|-------------------|
| Q1 | Vì sao tiến trình chết lúc 14:35? ROM giết (LMK / dọn app nền), app crash, hay owner thao tác? | quyết định có cần chặn tận gốc hay không |
| Q2 | Ở chế độ **chỉ bật phím-thoại** (Nav+HUD/Cast/badge tắt), app có foreground service nào không, `oom_score_adj` bao nhiêu? | nếu là cached process ⇒ ROM giết là chuyện thường ⇒ giữ tiến trình sống mới là gốc |
| Q3 | Một lần OTA `pm install -r` có để lại trạng thái kẹt không? | nếu CÓ ⇒ vá "nhả bind trước khi cài" là đúng gốc; nếu KHÔNG ⇒ bỏ hướng đó |
| Q4 | Tần suất: bao nhiêu lần chết mới ra một lần kẹt? | quyết định mức đầu tư (lưới an toàn vs refactor) |
| Q5 | `nohup … &` (đường tự mở lại của v1.40) có sống sau khi adbd đóng socket trên ROM này? | v1.40 phụ thuộc điều này; nếu không thì app nằm chết tới khi owner bấm icon |

## 4. Checklist ĐO — dán nguyên khi lên xe

`export VEH=<vehicle-ip>:5555` — **hỏi lại IP mỗi phiên, đừng đoán**; đừng ghi IP thật vào repo.

```bash
adb connect $VEH
PID=$(adb -s $VEH shell pidof com.byd.clusternav2 | tr -d '\r')
echo "pid=$PID"                                    # RỖNG = app không chạy (hoặc adb rớt — kiểm lại devices!)

# Q2 — mức bảo vệ khỏi bị giết + có FGS nào không
adb -s $VEH shell "cat /proc/$PID/oom_score_adj"   # 0..200 = được bảo vệ; 900+ = cached (dễ bị giết)
adb -s $VEH shell "dumpsys activity services com.byd.clusternav2 | grep -E 'ServiceRecord|isForeground'"
adb -s $VEH shell "dumpsys activity processes | grep -A4 'ProcessRecord{.*clusternav2'"

# Q1/Q4 — có bị giết không, và vì sao
adb -s $VEH shell "logcat -d -b events | grep -iE 'clusternav2' | tail -30"
adb -s $VEH shell "logcat -d -v time | grep -iE 'clusternav2' | grep -iE 'am_kill|am_proc_died|lowmem|Killing' | tail -20"
adb -s $VEH shell "ps -A -o PID,ETIME,NAME | grep clusternav2"     # tuổi tiến trình = lần chết gần nhất

# trạng thái trợ năng (so với §1)
adb -s $VEH shell "dumpsys accessibility | grep -E 'Bound services|Enabled services|Binding services'"
```

**Q3 (miễn phí, làm ngay lần OTA tới):** cài bản mới qua OTA → **mở app** → nếu hiện dialog *"Cần khởi động lại
app"* ⇒ OTA đúng là tác nhân (xác nhận bằng đo, không phải suy); nếu không hiện ⇒ loại hướng đó. Đọc kèm:
`dumpsys accessibility | grep -E 'Bound|Binding'` ngay sau khi cài xong.

**Q5:** khi dialog hiện, bấm **Khởi động lại** rồi bấm đồng hồ: app có tự mở lại sau ~4–5 giây không? Không tự
mở lại ⇒ `nohup` không sống trên ROM này ⇒ phải đổi cách mở lại.

## 5. Các hướng xử lý — ĐÃ CÂN, CHƯA CHỌN

| Hướng | Nội dung | Chi phí | Điều kiện để chọn |
|-------|----------|---------|-------------------|
| **A. Lưới an toàn** (ĐÃ LÀM, v1.40) | Nhận ra kẹt → bỏ toggle vô ích → khởi động lại tiến trình (1 lần/tiến trình + cooldown 10 phút) | đã xong | — |
| **B. Nhả bind TRƯỚC khi cài OTA** | Gỡ component khỏi `enabled_accessibility_services` → `pm install -r` → mở lại → thêm lại. Tái dùng `AccessibilityRebind.accessibilityRebindWrites` (đã có test) | ~15 dòng `UpdateChecker` + 1 dòng `RebindReceiver` | **Q3 = CÓ** |
| **C. Giữ tiến trình sống** | Khi phím-thoại bật thì có một FGS tối thiểu để LMK không dọn | 1 service + 1 notification | **Q2 = cached** và **Q1 = ROM giết** |
| **D. Tách service trợ năng ra tiến trình riêng** (`android:process`) | Main process chết không ảnh hưởng phím-thoại | **cao**: booster cự-ly GMaps đang ghi `NavAccessibilitySource` + gọi `TurnDistanceInterpolator.refine` **trong cùng tiến trình** ⇒ phải dựng IPC; prefs đa-tiến-trình; dòng trạng thái đọc chỗ khác | B+C không đủ, và **Q4** cho thấy kẹt thường xuyên |
| **E. Bỏ phụ thuộc a11y cho phím-thoại** | Đọc phím qua `getevent` trên shell dadb dài hạn | trung bình, và **cùng mức mong manh** (chết theo tiến trình mình) | chỉ khi A–D đều thua |

⚠ **Đừng chọn B/C/D trước khi có số ở §3.** Lịch sử tính năng này: đã vá **5 lần** (1.18 tự cấp lại setting ·
1.20 force-bind · 1.30 khôi phục OFF→ON · 1.32 chỉ báo + nút "Sửa ngay" · 1.40 khởi động lại tiến trình), mỗi
lần một điểm hỏng khác của cùng một dây phụ thuộc. Thêm tầng thứ 6 dựa trên phỏng đoán là cách chắc chắn để
tiếp tục lặp lại.
