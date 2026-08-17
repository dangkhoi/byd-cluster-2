package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.clusternav.Lang
import com.byd.clusternav.Prefs
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastState
import com.byd.clusternav.speedbadge.BadgeLayout

/**
 * Read-only Cast diagnostics screen.
 *
 * V2 (CastAndroidRuntime/CastFacade) removed — reads directly from SimpleCastRuntime state.
 * Shows current state, coordinator info, and last mutation outcome if available.
 */
class DiagActivity : Activity() {
    private lateinit var report: TextView
    private lateinit var status: TextView
    private lateinit var badgeInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { value: Int -> (value * resources.displayMetrics.density + .5f).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(0xFFF4F6F8.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Cluster Cast · Chẩn đoán"
            textSize = 22f
            setTextColor(Color.rgb(26, 31, 36))
        })
        root.addView(Button(this).apply {
            text = "Làm mới"
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener { refresh() }
        })
        root.addView(Button(this).apply {
            text = "Sao chép báo cáo"
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener { copyReport() }
        })
        root.addView(Button(this).apply {
            text = Lang.t("⬇ Kiểm tra cập nhật", "⬇ Check update")
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener { com.byd.clusternav.UpdateFlow.start(this@DiagActivity) { t, warn -> setStatus(t, warn) } }
        })
        // T5 (telemetry): force-render the speed-limit badge on the cluster (display 1) with a fixed 50 so the
        // driver can confirm ON-CAR whether the overlay draws over cast GMaps at all — without needing any
        // VietMap/Waze speed data. Uses NavigationSpeedSignOwner's independent debug overlay (the real
        // coordinator pipeline is untouched). A second button clears it.
        root.addView(Button(this).apply {
            text = Lang.t("Thử badge 50", "TEST BADGE 50")
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener {
                com.byd.clusternav.NavigationSpeedSignOwner.get(applicationContext).debugForceBadge(50)
                setStatus(Lang.t("Đã buộc badge 50 trên cụm (display 1)", "Forced badge 50 on cluster (display 1)"))
            }
        })
        root.addView(Button(this).apply {
            text = Lang.t("Ẩn badge", "HIDE badge")
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener {
                com.byd.clusternav.NavigationSpeedSignOwner.get(applicationContext).debugHideBadge()
                setStatus(Lang.t("Đã ẩn badge", "Badge hidden"))
            }
        })
        // Badge POSITION + SIZE controls: with the badge force-shown above, these persist to Prefs and call
        // debugRefreshBadgeLayout() so the driver watches it move/resize LIVE on the cluster and can park it
        // where the cast app doesn't cover it. The REAL speed-limit badge reads the same Prefs on its next
        // show(), so what you tune here is what the live badge uses.
        root.addView(TextView(this).apply {
            text = Lang.t("Chỉnh vị trí / cỡ badge", "Badge position / size")
            textSize = 15f
            setTextColor(Color.rgb(26, 31, 36))
            setPadding(0, dp(14), 0, dp(2))
        })
        root.addView(buttonRow(
            controlButton(Lang.t("↖ Trên-trái", "↖ Top-left")) { setBadgeCorner(BadgeLayout.CORNER_TOP_LEFT) },
            controlButton(Lang.t("↗ Trên-phải", "↗ Top-right")) { setBadgeCorner(BadgeLayout.CORNER_TOP_RIGHT) },
        ))
        root.addView(buttonRow(
            controlButton(Lang.t("↙ Dưới-trái", "↙ Bottom-left")) { setBadgeCorner(BadgeLayout.CORNER_BOTTOM_LEFT) },
            controlButton(Lang.t("↘ Dưới-phải", "↘ Bottom-right")) { setBadgeCorner(BadgeLayout.CORNER_BOTTOM_RIGHT) },
        ))
        root.addView(buttonRow(
            controlButton(Lang.t("Cỡ −10", "Size −10")) { nudgeBadgeSize(-BADGE_SIZE_STEP_DP) },
            controlButton(Lang.t("Cỡ +10", "Size +10")) { nudgeBadgeSize(BADGE_SIZE_STEP_DP) },
        ))
        root.addView(buttonRow(
            controlButton("X −8") { nudgeBadgeDx(-BADGE_NUDGE_STEP_DP) },
            controlButton("X +8") { nudgeBadgeDx(BADGE_NUDGE_STEP_DP) },
        ))
        root.addView(buttonRow(
            controlButton("Y −8") { nudgeBadgeDy(-BADGE_NUDGE_STEP_DP) },
            controlButton("Y +8") { nudgeBadgeDy(BADGE_NUDGE_STEP_DP) },
        ))
        badgeInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(0, 90, 120))
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(badgeInfo)
        refreshBadgeInfo()
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(0, 105, 92))
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(status)
        report = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(report)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onDestroy() { super.onDestroy() }

    private fun refresh() {
        val coordinator = SimpleCastRuntime.coordinator(applicationContext)
        val state = coordinator.state
        val value = buildString {
            appendLine("── Simplified Cast Diagnostics ──")
            appendLine()
            appendLine("state=$state")
            appendLine("architecture=SimplifiedV1 (single coordinator, no V2)")
            appendLine()

            // Display coordinator details
            when (state) {
                is SimpleCastState.CastingFull -> {
                    appendLine("target=${state.targetPkg}")
                    appendLine("appType=${state.appType}")
                    appendLine("displayConfig.wmSize=${state.displayConfig.wmSize}")
                    appendLine("displayConfig.overscan=${state.displayConfig.overscan}")
                    appendLine("displayConfig.density=${state.displayConfig.density}")
                    state.taskId?.let { appendLine("taskId=$it") }
                }
                is SimpleCastState.CastingSplit -> {
                    state.left?.let {
                        appendLine("left.pkg=${it.pkg}")
                        appendLine("left.wmSize=${it.displayConfig.wmSize}")
                    }
                    state.right?.let {
                        appendLine("right.pkg=${it.pkg}")
                        appendLine("right.wmSize=${it.displayConfig.wmSize}")
                    }
                }
                is SimpleCastState.Error -> {
                    appendLine("error=${state.message}")
                }
                else -> { /* no extra details for Off/Opening/Idle/Stopping/Closing */ }
            }

            appendLine()
            appendLine("── prefs ──")
            val prefs = coordinator.prefs
            appendLine("autoStart=${prefs.autoStartEnabled()}")
            appendLine("autoStartPkg=${prefs.autoStartPackage() ?: "(none)"}")
            appendLine("autoStartSplit=${prefs.autoStartSplitEnabled()}")
            appendLine("splitRatioLeft=${prefs.splitRatioLeftPercent()}%")
            appendLine("dozeWhitelist=${prefs.dozeWhitelistApplied()}")
        }
        report.text = value
    }

    private fun copyReport() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Cluster Cast diagnostics", report.text))
        android.widget.Toast.makeText(this, "Đã sao chép", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ─── Badge position/size controls ───────────────────────────────────────
    // Each control persists to Prefs then re-applies the forced badge LIVE via the owner's debug overlay.
    // The real ClusterSpeedBadgePort overlay shares SpeedBadgeOverlay and re-reads these same Prefs on its
    // next show(), so tuning here is what the live speed-limit badge will use.
    private fun setBadgeCorner(corner: Int) {
        Prefs.setBadgeCorner(applicationContext, corner)
        applyBadge()
    }

    private fun nudgeBadgeSize(deltaDp: Int) {
        val ctx = applicationContext
        Prefs.setBadgeSizeDp(ctx, Prefs.badgeSizeDp(ctx) + deltaDp)   // Prefs clamps 60..240 via BadgeLayout
        applyBadge()
    }

    private fun nudgeBadgeDx(deltaDp: Int) {
        val ctx = applicationContext
        Prefs.setBadgeDx(ctx, (Prefs.badgeDx(ctx) + deltaDp).coerceIn(0, BADGE_NUDGE_MAX_DP))
        applyBadge()
    }

    private fun nudgeBadgeDy(deltaDp: Int) {
        val ctx = applicationContext
        Prefs.setBadgeDy(ctx, (Prefs.badgeDy(ctx) + deltaDp).coerceIn(0, BADGE_NUDGE_MAX_DP))
        applyBadge()
    }

    /** Push the freshly-saved Prefs to the force-shown badge and refresh the on-screen readout. */
    private fun applyBadge() {
        com.byd.clusternav.NavigationSpeedSignOwner.get(applicationContext).debugRefreshBadgeLayout()
        refreshBadgeInfo()
    }

    private fun refreshBadgeInfo() {
        if (!::badgeInfo.isInitialized) return
        val ctx = applicationContext
        val corner = Prefs.badgeCorner(ctx)
        val cornerNames = listOf(
            Lang.t("Trên-trái", "Top-left"), Lang.t("Trên-phải", "Top-right"),
            Lang.t("Dưới-trái", "Bottom-left"), Lang.t("Dưới-phải", "Bottom-right"),
        )
        val name = cornerNames.getOrElse(corner) { "?" }
        badgeInfo.text = Lang.t(
            "Vị trí: $name · cỡ ${Prefs.badgeSizeDp(ctx)}dp · lệch X=${Prefs.badgeDx(ctx)} Y=${Prefs.badgeDy(ctx)}",
            "Layout: $name · size ${Prefs.badgeSizeDp(ctx)}dp · offset X=${Prefs.badgeDx(ctx)} Y=${Prefs.badgeDy(ctx)}",
        )
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        return row
    }

    private fun controlButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minimumHeight = (44 * resources.displayMetrics.density + .5f).toInt()
        setOnClickListener { onClick() }
    }

    // ─── Check-for-update status sink ────────────────────────────────────────
    // The interactive check → confirm → download → install flow lives in the shared [UpdateFlow]
    // helper (invoked by the button above and by MainActivity). This screen only renders status.
    private fun setStatus(text: String, warn: Boolean = false) {
        status.text = text
        status.setTextColor(if (warn) Color.rgb(176, 0, 32) else Color.rgb(0, 105, 92))
    }

    private companion object {
        const val BADGE_SIZE_STEP_DP = 10
        const val BADGE_NUDGE_STEP_DP = 8
        const val BADGE_NUDGE_MAX_DP = 600   // keep the badge reachable on-screen so a nudge can't lose it
    }
}
