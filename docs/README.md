# ClusterNav 2.0 — Docs Index (INDEX canonical)

> **Trạng thái**: Current · **Cập nhật**: 2026-08-19 · **Mục đích**: Bản đồ MỌI tài liệu hiện hành theo 9-loại taxonomy (R4). Không có trong index = archive/stale, KHÔNG authoritative (R0).

**(VI)** Đây là **nguồn map tài liệu duy nhất** của repo. Đọc file này trước → rồi mở doc cụ thể. Task = `PROJECT-BACKLOG.md`. Luật bền = `../.kiro/steering/`.
**(EN)** This is the repo's **single documentation map**. Read this first → then open the specific doc. Tasks live in `PROJECT-BACKLOG.md`; durable rules in `../.kiro/steering/`.

**Legend — trạng thái:** `Current` = hiện hành/authoritative · `Session` = handoff phiên (tạm) · `Historical` = lineage/context, giữ tại chỗ, KHÔNG authoritative · `Pending` = sẽ tạo (stage khác).

**9-loại taxonomy (R4):** 1) Index · 2) Backlog · 3) Rules/Steering · 4) Overview · 5) Spec · 6) Diagnostics · 7) Guide · 8) ADR · 9) Handoff · (+ `archive/` = trạng thái doc bị thay thế).

---

## 1) Index

| Doc | Mục đích | Trạng thái | Cập nhật |
|-----|----------|-----------|----------|
| [`README.md`](README.md) (file này) | Map canonical mọi doc hiện hành theo 9-loại | Current | 2026-08-19 |

## 2) Backlog

| Doc | Mục đích | Trạng thái | Cập nhật |
|-----|----------|-----------|----------|
| [`PROJECT-BACKLOG.md`](PROJECT-BACKLOG.md) | Nguồn DUY NHẤT cho task (ID · việc · trạng thái · ngày) | Current | 2026-08-19 |

## 3) Rules / Steering

| Doc | Mục đích | Trạng thái | Cập nhật |
|-----|----------|-----------|----------|
| [`../.kiro/steering/documentation-and-backlog.md`](../.kiro/steering/documentation-and-backlog.md) | Kỷ luật doc + backlog + 9-loại taxonomy (bắt buộc mọi phiên) | Current | 2026-08-19 |
| [`../.kiro/steering/product-team-workflow.md`](../.kiro/steering/product-team-workflow.md) | Quy trình làm việc như một team (PO→UX→Dev→QA→Review) | Current | ~ |
| [`../.kiro/steering/project-context.md`](../.kiro/steering/project-context.md) | Tóm tắt luôn-bật (kiến trúc + nguồn nav + HAL/CAN + map file + trạng thái) | Current | 2026-08-19 |

> Steering toàn cục (không nằm trong repo): `~/.kiro/steering/` (workflow, pre-commit-security, PROMPT_TEMPLATE, image-reading-subagent). Chỉ tham chiếu, không sửa ở đây.

## 4) Overview

| Doc | Mục đích | Trạng thái | Cập nhật |
|-----|----------|-----------|----------|
| [`../README.md`](../README.md) | Landing dự án — 2 nhánh, trạng thái 1.0/1.30, cài đặt/OTA (VI+EN) | Current | 2026-08-17 |
| [`CLOSEOUT-2026-08-16.md`](CLOSEOUT-2026-08-16.md) | Đánh giá đóng dự án 1.30 — 6 bản sửa cuối + giới hạn đã biết | Current | 2026-08-16 |
| [`HISTORICAL-ARTIFACTS.md`](HISTORICAL-ARTIFACTS.md) | Hồ sơ cách ly artifact lịch sử (APK/ảnh cũ) + cổng release | Current | 2026-08-16 |

## 5) Spec — `specs/*.html` (duyệt TRƯỚC khi code)

### Current — authoritative

| Doc | Mục đích | Trạng thái | Cập nhật |
|-----|----------|-----------|----------|
| [`specs/upcoming-speed-limit-badge.html`](specs/upcoming-speed-limit-badge.html) | Vẽ "giới hạn sắp tới + cự ly" (VietMap) lên cụm (active — A9/B2) | Current | 2026-08-18 |
| [`specs/waze-vietmap-screen-capture.html`](specs/waze-vietmap-screen-capture.html) | Screen-capture nav (Waze arrow + VietMap camera) học OpenBYD — B3, **Chờ duyệt**, 4 case | Current | 2026-08-19 |
| [`specs/speed-limit-cluster-hud-oncar-ready.html`](specs/speed-limit-cluster-hud-oncar-ready.html) | Speed-limit cluster badge + HAL port + HUD probe (on-car ready) | Current | 2026-08-17 |
| [`specs/speed-badge-placement-vietmap-logging.html`](specs/speed-badge-placement-vietmap-logging.html) | UI đặt vị trí badge + dời nguồn VietMap + log toàn tín hiệu | Current | 2026-08-17 |
| [`specs/v2-accessibility-navsource-handoff.html`](specs/v2-accessibility-navsource-handoff.html) | a11y NavScreenSource đa app (GMaps/Waze/VietMap) | Current | 2026-08-17 |
| [`specs/waze-vietmap-signal-revival.html`](specs/waze-vietmap-signal-revival.html) | Revival Waze-Mod + VietMap widget + speed-limit signal (lên 1.30) | Current | 2026-08-17 |
| [`specs/clusternav-two-track-final-plan.html`](specs/clusternav-two-track-final-plan.html) | Kế hoạch 2 nhánh chốt + evidence gates | Current | 2026-07-26 |
| [`specs/cluster-cast-rebaseline.html`](specs/cluster-cast-rebaseline.html) | Hợp đồng Cast canonical (re-baseline) | Current | 2026-07-26 |
| [`specs/clusternav-uxui-rebaseline.html`](specs/clusternav-uxui-rebaseline.html) | UX 2-card + hợp đồng Navigation (re-baseline) | Current | 2026-07-30 |
| [`specs/dead-reckon-revalidation.html`](specs/dead-reckon-revalidation.html) | Quyết định REMOVE Dead-Reckon + review debt | Current | 2026-07-27 |
| [`specs/notif-grant-docs-voicekey-1.13.html`](specs/notif-grant-docs-voicekey-1.13.html) | Spec 1.13: notification-grant · docs · voice-key | Current | 2026-08-13 |
| [`specs/clusternav-closeout-1.28.html`](specs/clusternav-closeout-1.28.html) | Spec đóng dự án 1.30 (nền của CLOSEOUT) | Current | 2026-08-16 |
| [`specs/_template.html`](specs/_template.html) | Template spec Kiro-style (10 section chuẩn + §9 Nhật ký triển khai) — starting point cho spec mới (workflow.md §3) | Current | 2026-08-19 |

### Historical / lineage — giữ tại chỗ, context-only (R0: không authoritative)

> Các spec dưới đây mô tả build/điều tra lịch sử. Giữ nguyên trong `specs/` làm lineage; **không** phải baseline hiện hành trừ khi một spec Current ở trên promote lại.

- [`specs/cluster-cast-simplified.html`](specs/cluster-cast-simplified.html) — Cast simplified architecture (tiền thân của rebaseline)
- [`specs/cast-architecture-cleanup.html`](specs/cast-architecture-cleanup.html) — gỡ v2 stack, gộp 1 path simplified
- [`specs/cast-simplified-active-app-toggle.html`](specs/cast-simplified-active-app-toggle.html) — nút nổi = chiếu app đang mở
- [`specs/cast-boot-recovery-and-app-manager-entrypoint.html`](specs/cast-boot-recovery-and-app-manager-entrypoint.html) — boot recovery + App Manager
- [`specs/cast-enable-toggle.html`](specs/cast-enable-toggle.html) — master enable/disable Cast
- [`specs/cast-freeform-resize-split.html`](specs/cast-freeform-resize-split.html) — freeform resize/split/per-app
- [`specs/cast-one-mode-and-three-zone-bubble.html`](specs/cast-one-mode-and-three-zone-bubble.html) — một chế độ + nút nổi 3 ô
- [`specs/cast-recovery-honesty-and-multi-occupant.html`](specs/cast-recovery-honesty-and-multi-occupant.html) — trung thực khi kẹt + đa-chủ
- [`specs/cast-resize-dpi-bubble-fixes.html`](specs/cast-resize-dpi-bubble-fixes.html) — resize persistence · DPI · bubble toggle
- [`specs/cast-secondary-app-corner-overlay.html`](specs/cast-secondary-app-corner-overlay.html) — overlay góc cụm cho app phụ
- [`specs/cast-nav-ux-release-v104.html`](specs/cast-nav-ux-release-v104.html) — Cast+Nav UX polish v1.04
- [`specs/cluster-cast-v036.html`](specs/cluster-cast-v036.html) — v0.36 ladder chiếu, chặn PIP
- [`specs/cluster-cast-v070-manual-cold-intent.html`](specs/cluster-cast-v070-manual-cold-intent.html) — v0.70 manual cold intent
- [`specs/cluster-cast-v071-product-completion.html`](specs/cluster-cast-v071-product-completion.html) — v0.71 product completion
- [`specs/cluster-nav-4mode-restore.html`](specs/cluster-nav-4mode-restore.html) — khôi phục AMAP nav-trên-cụm 4 mode
- [`specs/clusternav-v102-review-remediation.html`](specs/clusternav-v102-review-remediation.html) — v1.03 review remediation
- [`specs/clusternav-v103-remediation.html`](specs/clusternav-v103-remediation.html) — v1.03 remediation + letterbox
- [`specs/dual-track-2026-07-23.html`](specs/dual-track-2026-07-23.html) — kế hoạch 2 nhánh v0.60 (07-23)
- [`specs/freeze-proof-cluster-switch.html`](specs/freeze-proof-cluster-switch.html) — đổi app cụm chống freeze v0.66
- [`specs/hud-keepalive-interp-log-1.15.html`](specs/hud-keepalive-interp-log-1.15.html) — 1.15 HUD keep-alive + interp log
- [`specs/nav-cluster-op39-selfdiagnose.html`](specs/nav-cluster-op39-selfdiagnose.html) — op39 "Giữa + ETA" self-diagnose
- [`specs/nav-oncar-fixes-1.14.html`](specs/nav-oncar-fixes-1.14.html) — 1.14 on-car fixes
- [`specs/seal-hud-sign-candidate-expansion.html`](specs/seal-hud-sign-candidate-expansion.html) — HUD/sign candidate expansion
- [`specs/seal-hud-sign-vehicle-test-t10.html`](specs/seal-hud-sign-vehicle-test-t10.html) — kế hoạch T10 HUD + biển tốc độ
- [`specs/seal-nav-hud-speed-sign-offcar.html`](specs/seal-nav-hud-speed-sign-offcar.html) — Seal nav HUD + speed sign off-car
- [`specs/vietmap-widget-bridge.html`](specs/vietmap-widget-bridge.html) — VietMap widget bridge POC
- [`specs/voicekey-rework-1.19.html`](specs/voicekey-rework-1.19.html) — 1.19 voice-key UX rework
- [`specs/windshield-hud-enable.html`](specs/windshield-hud-enable.html) — bật dẫn đường trên HUD kính
- [`specs/cast-ui-state-v2.schema.json`](specs/cast-ui-state-v2.schema.json) — schema JSON hỗ trợ (artifact)

## 6) Diagnostics — `diagnostics/*` (finding có BẰNG CHỨNG + ngày)

### Current

| Doc | Mục đích | Trạng thái | Cập nhật |
|-----|----------|-----------|----------|
| [`diagnostics/VEHICLE-TEST-V2.md`](diagnostics/VEHICLE-TEST-V2.md) | Checklist thử trên xe + ma trận Stage 11 (execution NOT STARTED) | Current | 2026-07-26 |
| [`diagnostics/arrow-validation-teammate-2026-08-18.md`](diagnostics/arrow-validation-teammate-2026-08-18.md) | Xác thực 18/18 mũi tên (blind bitmap vs answer-key) | Current | 2026-08-18 |
| [`diagnostics/distance-interpolation-validation-2026-08-18.md`](diagnostics/distance-interpolation-validation-2026-08-18.md) | Kiểm cự ly 2 bên + nội suy km→turn (a11y screenRead) | Current | 2026-08-18 |
| [`diagnostics/oncar-session-2026-08-16.md`](diagnostics/oncar-session-2026-08-16.md) | Tổng hợp + TODO phiên on-car 2026-08-16 | Current | 2026-08-16 |
| [`diagnostics/app-code-updates-2026-08-16.md`](diagnostics/app-code-updates-2026-08-16.md) | Sửa CODE rút từ phiên on-car 2026-08-16 (file:line + acceptance) | Current | 2026-08-16 |
| [`diagnostics/nav-icon-mapping-2026-08-16.html`](diagnostics/nav-icon-mapping-2026-08-16.html) | Mapping icon dẫn đường (GMaps → cụm AMAP / HUD CAN) | Current | 2026-08-16 |
| [`diagnostics/nav-output-architecture-2026-08-16.html`](diagnostics/nav-output-architecture-2026-08-16.html) | Kiến trúc 2 đường ra Navigation + bảng field | Current | 2026-08-16 |
| [`diagnostics/re-maneuver-icon-tables-2026-08-14.md`](diagnostics/re-maneuver-icon-tables-2026-08-14.md) | Bảng RE icon AMAP/HUD CAN + enrich Maneuver | Current | 2026-08-14 |
| [`diagnostics/gemini-assistant-voicekey-oncar-2026-08-13.md`](diagnostics/gemini-assistant-voicekey-oncar-2026-08-13.md) | Thủ tục on-car Gemini trợ lý + nút mic → Gemini | Current | 2026-08-13 |
| [`diagnostics/hud-sign-re/README.md`](diagnostics/hud-sign-re/README.md) | Workspace RE HUD + speed-sign T0–T9 (corpus, evidence, expansion) | Current | 2026-08-18 |

### Historical / context — giữ tại chỗ

- [`diagnostics/oncar-handoff-voicekey-2026-08-14.md`](diagnostics/oncar-handoff-voicekey-2026-08-14.md) — handoff on-car voice-key 1.19 (đã qua)
- `diagnostics/artifacts/` — evidence logcat/env cast 2026-07-30 (đọc-only)
- Scripts diagnostics (tooling, không phải doc): `diagnostics/nav-log.ps1`, `diagnostics/nav-debug.ps1`, `diagnostics/autotest.ps1`, `diagnostics/cluster-cast-test.ps1`

## 7) Guide — `HUONG-DAN-*` (user / anh em, song ngữ)

| Doc | Mục đích | Trạng thái | Cập nhật |
|-----|----------|-----------|----------|
| [`HUONG-DAN.md`](HUONG-DAN.md) | Hướng dẫn dùng ClusterNav (bật Nav+HUD, quyền, cluster mode, voice-key) | Current | 2026-08-16 |
| [`HUONG-DAN-LAY-LOG.md`](HUONG-DAN-LAY-LOG.md) | Lấy log + ảnh sau lái thử (Cách A không máy tính / B dùng máy tính) | Current | 2026-08-17 |
| [`HUONG-DAN-LAY-LOG-WINDOWS.html`](HUONG-DAN-LAY-LOG-WINDOWS.html) | Lấy log ClusterNav bằng máy Windows | Current | 2026-08-19 |
| [`HUONG-DAN-THU-DATA-HUD.html`](HUONG-DAN-THU-DATA-HUD.html) | Thu thập data HUD (so sánh provisioning xe anh em) | Current | 2026-08-18 |

## 8) ADR — `decisions/NNNN-*.md` (quyết định KIẾN TRÚC xuyên suốt)

| Doc | Mục đích | Trạng thái | Cập nhật |
|-----|----------|-----------|----------|
| [`decisions/README.md`](decisions/README.md) | Index ADR + hướng dẫn định dạng (Context·Decision·Consequences·Status·Date; khi nào mở ADR) | Current | 2026-08-19 |
| [`decisions/0001-nav-source-strategy.md`](decisions/0001-nav-source-strategy.md) | Chiến lược nguồn nav per-app (GMaps notif · VietMap widget+a11y · Waze screen-capture) + adapter trung lập | Current | 2026-08-19 |
| [`decisions/0002-hud-nav-coding-locked.md`](decisions/0002-hud-nav-coding-locked.md) | HUD kính lái = coding BYD (`0x38B00030` NOT provisioned), không phải bug app | Current | 2026-08-19 |
| [`decisions/0003-datacollection-logging-default-off.md`](decisions/0003-datacollection-logging-default-off.md) | Thu thập dữ liệu (log + ảnh) mặc định OFF + storage cap ~150 MB | Current | 2026-08-19 |

## 9) Handoff — `_handoff/*.md` (tóm tắt phiên, tạm)

> Handoff = ghi chép phiên (tạm/lịch sử theo R4). Đây là bộ handoff phiên hiện hành của nhánh `feat/speed-limit-badge-hal-hud`; bản cũ đã ở `archive/_handoff/`.

| Doc | Mục đích | Trạng thái | Cập nhật |
|-----|----------|-----------|----------|
| [`_handoff/stage-e1-done.md`](_handoff/stage-e1-done.md) | Handoff E1 — refactor docs 9-loại + tạo INDEX (file report này) | Session | 2026-08-19 |
| [`_handoff/stage-e2-done.md`](_handoff/stage-e2-done.md) | Handoff E2 — tạo `../.kiro/steering/project-context.md` (steering luôn-bật) | Session | 2026-08-19 |
| [`_handoff/stage-e4-done.md`](_handoff/stage-e4-done.md) | Handoff E4 — template §Nhật ký triển khai + `decisions/` ADR (README + 3 ADR) | Session | 2026-08-19 |
| [`_handoff/off-car-plan-2026-08-17.md`](_handoff/off-car-plan-2026-08-17.md) | Tổng hợp phiên on-car 08-17 + kế hoạch off-car | Session | 2026-08-17 |
| [`_handoff/progress-2026-08-18-findings-offcar-oncar.md`](_handoff/progress-2026-08-18-findings-offcar-oncar.md) | Findings đã implement off-car + kế hoạch verify on-car | Session | 2026-08-18 |
| [`_handoff/data-collection-drive-guide-2026-08-18.md`](_handoff/data-collection-drive-guide-2026-08-18.md) | Hướng dẫn chạy thu data (cho anh em) | Session | 2026-08-18 |
| [`_handoff/morning-handoff-2026-08-18.md`](_handoff/morning-handoff-2026-08-18.md) | Handoff sáng 08-18 (chạy autonomous đêm 08-17) | Session | 2026-08-18 |
| [`_handoff/stage-upcoming-badge-done.md`](_handoff/stage-upcoming-badge-done.md) | Stage: upcoming speed-limit + distance badge — DONE | Session | 2026-08-18 |
| [`_handoff/stage-logging-off-done.md`](_handoff/stage-logging-off-done.md) | Stage: data-collection logging OFF mặc định + storage cap — DONE | Session | 2026-08-18 |
| [`_handoff/stage-cdesc-done.md`](_handoff/stage-cdesc-done.md) | Stage: content-description fallback (VietMap/Waze) — DONE | Session | 2026-08-18 |
| [`_handoff/stage-rawnotif-done.md`](_handoff/stage-rawnotif-done.md) | Stage: raw-notif capture 5 gói nav — DONE | Session | 2026-08-18 |
| [`_handoff/stage-vmalert-done.md`](_handoff/stage-vmalert-done.md) | Stage: VMAlert capture (upLimit/upDist) — DONE | Session | 2026-08-18 |
| [`_handoff/stage-capture-done.md`](_handoff/stage-capture-done.md) | Stage: data-capture enhancements — DONE | Session | 2026-08-18 |
| [`_handoff/stage-badge-done.md`](_handoff/stage-badge-done.md) | Stage: badge lifecycle fix + toggle — DONE | Session | 2026-08-18 |
| [`_handoff/stage-waze-b2-done.md`](_handoff/stage-waze-b2-done.md) | Stage: Waze logcat tag fix + Track B2 screenshot | Session | 2026-08-17 |
| [`_handoff/stage-logging-done.md`](_handoff/stage-logging-done.md) | Stage: log ALL VietMap signals — DONE | Session | 2026-08-17 |
| [`_handoff/stage-ui-done.md`](_handoff/stage-ui-done.md) | Stage: visual badge-placement UI + relocate VietMap source | Session | 2026-08-17 |
| [`_handoff/stage-foundation-done.md`](_handoff/stage-foundation-done.md) | Stage: badge foundation + BUG-1 unify — DONE | Session | 2026-08-17 |
| [`_handoff/handoff-2026-08-17-2.0-isolation-cast-cleanup-research.md`](_handoff/handoff-2026-08-17-2.0-isolation-cast-cleanup-research.md) | Handoff: 2.0 isolation + cast cleanup + speed/HUD research | Session | 2026-08-17 |
| [`_handoff/handoff-2026-08-17-repo-split-and-revival.md`](_handoff/handoff-2026-08-17-repo-split-and-revival.md) | Handoff: tách repo base + revive Waze/VietMap signal | Session | 2026-08-17 |
| [`_handoff/next-session-A-coding-2026-08-16.md`](_handoff/next-session-A-coding-2026-08-16.md) | Handoff A — coding (update app) nguồn phiên 08-16 | Session | 2026-08-16 |
| [`_handoff/next-session-B-research-hud-2026-08-16.md`](_handoff/next-session-B-research-hud-2026-08-16.md) | Handoff B — research HUD kính (owner vs Sealion 6) | Session | 2026-08-16 |
| [`_handoff/hud-cluster-injection-findings-2026-08-10.md`](_handoff/hud-cluster-injection-findings-2026-08-10.md) | HUD/cluster injection — on-car findings & handoff | Session | 2026-08-10 |

---

## Design / Evidence & Research / Reference (context — giữ tại chỗ, ngoài 9-loại chính)

> Các thư mục làm việc cũ, giữ làm bằng chứng/nghiên cứu lineage. Context-only (R0: không authoritative).

- [`design/navigation-hud-evidence.html`](design/navigation-hud-evidence.html) — bằng chứng Stage 2 nhánh Navigation + HUD
- [`design/cluster-cast-evidence.html`](design/cluster-cast-evidence.html) — bằng chứng Stage 2 nhánh Cluster Cast
- [`research/gps-dead-reckon-tunnel.html`](research/gps-dead-reckon-tunnel.html) — nghiên cứu mất GPS trong hầm (Dead-Reckon đã REMOVE)
- [`reference/dashcast-projection-recipe.md`](reference/dashcast-projection-recipe.md) — recipe cast lịch sử (ARCHIVED; thay bằng `specs/cluster-cast-rebaseline.html`)
- [`refactor-car-execution/index.html`](refactor-car-execution/index.html) — workspace refactor car-execution (spec/progress/fixtures/evidence — lịch sử)
- `images/` — ảnh minh hoạ dùng trong guide/README (assets, không phải doc)

## Archive — `archive/` (trạng thái: doc bị thay thế; giữ history, KHÔNG authoritative)

> **(VI)** Toàn bộ doc lịch sử đã nghỉ hưu nằm trong [`archive/`](archive/) — gồm `archive/diagnostics/`, `archive/review/`, `archive/_handoff/`. Không liệt kê chi tiết từng file ở đây; lịch sử git được giữ nguyên. **Không** dùng làm authoritative.
> **(EN)** All retired historical docs live under [`archive/`](archive/) (`archive/diagnostics/`, `archive/review/`, `archive/_handoff/`). Not enumerated here; git history preserved. Not authoritative.
