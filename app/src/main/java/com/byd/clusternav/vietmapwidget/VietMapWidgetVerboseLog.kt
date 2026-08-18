package com.byd.clusternav.vietmapwidget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.View
import com.byd.clusternav.NavLog
import java.io.File

/**
 * Bridge-side orchestration for the verbose VietMap signal capture (spec §4.4). Split out of
 * [VietMapWidgetBridge] so the bridge keeps only thin, unconditional call sites (and stays within the
 * 500-LOC guardrail). Every function here is self-gated on [NavLog.verbose] and self-wrapped in
 * `runCatching` so capture can NEVER affect navigation or the widget pipeline. Wires
 * [VietMapWidgetExtraction] (view / pixel reads) to [VietMapSignalLog] (the CSV + PNG sinks).
 */
internal object VietMapWidgetVerboseLog {

    /**
     * C2 — dump every TextView / ImageView (including fields the app does not yet parse) of the
     * just-applied RemoteViews tree. The tree walk runs on the caller (main) thread; the CSV write
     * is handed off-thread by [VietMapSignalLog].
     */
    fun logHostViewTree(appContext: Context, extraction: VietMapWidgetExtraction, root: View) {
        if (!NavLog.verbose) return
        runCatching { VietMapSignalLog.logViews(appContext, extraction.dumpAllViews(root)) }
    }

    /**
     * C3 — save each VISIBLE alert icon to `files/diag/vietmap-alert-<hash>.png`, once per unique hash.
     * Must be called on the thread that owns the views (main) so drawable capture is safe; the PNG
     * encode + write are deferred off-thread inside [VietMapWidgetExtraction.saveAlertImagePng].
     */
    fun logAlertIcons(
        appContext: Context,
        extraction: VietMapWidgetExtraction,
        root: AppWidgetHostView,
        raw: VietMapWidgetRawValues,
        firstHash: String?,
        secondHash: String?,
    ) {
        if (!NavLog.verbose) return
        runCatching {
            val dir = File(appContext.getExternalFilesDir(null), "diag")
            val (img1, img2) = extraction.alertImageViews(root)
            if (img1 != null && raw.firstAlertImageVisible && firstHash != null) {
                extraction.saveAlertImagePng(img1, firstHash, dir)
            }
            if (img2 != null && raw.secondAlertImageVisible && secondHash != null) {
                extraction.saveAlertImagePng(img2, secondHash, dir)
            }
        }
    }

    /**
     * C1 — one signal row per DISTINCT published snapshot. a1/a2 are read from [combinedRaw]'s
     * first/second fields (NOT [snapshot].alerts, which is `filterNotNull()`-collapsed and would
     * mis-order a1 ↔ a2); the alert speed-limit texts are parsed to Int the same way the app reads them.
     */
    fun logPublishedSnapshot(
        appContext: Context,
        freshness: VietMapWidgetFreshness,
        snapshot: VietMapWidgetSnapshot,
        combinedRaw: VietMapWidgetRawValues,
    ) {
        if (!NavLog.verbose) return
        runCatching {
            VietMapSignalLog.log(
                appContext,
                freshness = freshness.name,
                providerVersion = snapshot.providerVersion,
                currentSpeedKph = snapshot.currentSpeedKph,
                speedLimitKph = snapshot.speedLimitKph,
                a1Limit = VietMapWidgetTextParser.parseSpeedLimit(combinedRaw.firstAlertSpeedLimitText),
                a1Dist = combinedRaw.firstAlertDistanceText,
                a1ImgVisible = combinedRaw.firstAlertImageVisible,
                a1ImgHash = combinedRaw.firstAlertImageHash,
                a2Limit = VietMapWidgetTextParser.parseSpeedLimit(combinedRaw.secondAlertSpeedLimitText),
                a2Dist = combinedRaw.secondAlertDistanceText,
                a2ImgVisible = combinedRaw.secondAlertImageVisible,
                a2ImgHash = combinedRaw.secondAlertImageHash,
                // ALERT_FULL upcoming/enforced limit ahead — read from the already-parsed snapshot fields.
                upLimit = snapshot.upcomingLimitKph,
                upDist = snapshot.upcomingDistanceText,
                up2Limit = snapshot.secondUpcomingLimitKph,
                up2Dist = snapshot.secondUpcomingDistanceText,
            )
        }
    }
}
