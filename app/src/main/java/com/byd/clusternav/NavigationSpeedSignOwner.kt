package com.byd.clusternav

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.contracts.SpeedLimitSource
import com.byd.clusternav.navigation.SpeedSignLifecycleCoordinator
import com.byd.clusternav.navigation.SpeedSignOutput
import com.byd.clusternav.speedbadge.ClusterSpeedBadgePort
import com.byd.clusternav.speedbadge.HalSpeedSignPort

/**
 * Android lifecycle facade for the typed speed-sign coordinator.
 *
 * Cluster port renders a speed-limit badge overlay on display 1 (TYPE_APPLICATION_OVERLAY).
 * HUD port writes STATISTICS_ISA_CURRENT_ROAD_SPEED_LIMIT_SET (0x4B40001C) via BydHal reflection.
 * Both ports degrade gracefully to no-op off-car (no display 1 / no HAL device).
 */
class NavigationSpeedSignOwner private constructor(private val appContext: Context) : AutoCloseable {
    private val processEpoch = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
    private val coordinator = SpeedSignLifecycleCoordinator(
        clusterPort = ClusterSpeedBadgePort(appContext),
        hudPort = HalSpeedSignPort(appContext),
        monotonicNowMs = SystemClock::elapsedRealtime,
    ).also { it.onProcessRestart(processEpoch) }

    fun syncFromPrefs() {
        onSourceSelected(Prefs.speedLimitSource(appContext))
        onOutputEnabled(SpeedSignOutput.CLUSTER, Prefs.lane(appContext))
        onOutputEnabled(SpeedSignOutput.HUD, Prefs.hud(appContext))
        onMasterEnabled(Prefs.enabled(appContext))
    }

    fun onMasterEnabled(enabled: Boolean) {
        coordinator.onMasterEnabled(enabled)
    }

    fun onSourceSelected(source: SpeedLimitSource) {
        coordinator.onSourceSelected(source)
    }

    fun onOutputEnabled(output: SpeedSignOutput, enabled: Boolean) {
        coordinator.onOutputEnabled(output, enabled)
    }

    fun onSpeedLimit(
        source: SpeedLimitSource,
        valueKph: Int,
        observedAtMonotonicMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = coordinator.onSpeedLimit(source, valueKph, observedAtMonotonicMs, processEpoch)

    fun onProviderDisconnected(source: SpeedLimitSource) {
        coordinator.onProviderDisconnected(source)
    }

    fun onSourceStopped(source: SpeedLimitSource) {
        coordinator.onSourceStopped(source)
    }

    override fun close() {
        coordinator.close()
    }

    companion object {
        private const val TAG = "NavigationSpeedSign"
        @Volatile private var instance: NavigationSpeedSignOwner? = null

        fun get(context: Context): NavigationSpeedSignOwner = instance ?: synchronized(this) {
            instance ?: NavigationSpeedSignOwner(context.applicationContext).also {
                instance = it
                it.syncFromPrefs()
                Log.i(TAG, "typed lifecycle active with ClusterSpeedBadge + HalSpeedSign ports")
            }
        }
    }
}
