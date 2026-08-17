package com.byd.clusternav.speedbadge

import android.content.Context
import android.util.Log
import com.byd.clusternav.contracts.SpeedLimitFrame
import com.byd.clusternav.navigation.SpeedSignOutput
import com.byd.clusternav.navigation.SpeedSignPort
import com.byd.clusternav.navigation.SpeedSignSubmission

/**
 * SpeedSignPort for CLUSTER output: renders the speed-limit badge as a
 * TYPE_APPLICATION_OVERLAY on display 1 (the instrument cluster).
 *
 * If display 1 is unavailable (off-car), degrades to no-op silently after one log line.
 * Generation fencing matches NoopSpeedSignPort semantics exactly.
 */
class ClusterSpeedBadgePort(context: Context) : SpeedSignPort {

    companion object {
        private const val TAG = "ClusterSpeedBadge"
    }

    override val output: SpeedSignOutput = SpeedSignOutput.CLUSTER

    private val lock = Any()
    private var acceptedGeneration = 0L
    private val overlay = SpeedBadgeOverlay(context.applicationContext)

    override fun publish(frame: SpeedLimitFrame, generation: Long): SpeedSignSubmission = synchronized(lock) {
        require(frame.value != null) { "publish requires an active frame" }
        if (generation < acceptedGeneration) return SpeedSignSubmission.STALE_DROPPED
        acceptedGeneration = generation
        val v = frame.value ?: return SpeedSignSubmission.STALE_DROPPED
        overlay.show(v, frame.signType)
        Log.d(TAG, "show $v km/h gen=$generation")
        SpeedSignSubmission.ACCEPTED
    }

    override fun replaceWithClear(frame: SpeedLimitFrame, generation: Long): SpeedSignSubmission = synchronized(lock) {
        require(frame.value == null) { "replaceWithClear requires a clear frame" }
        if (generation < acceptedGeneration) return SpeedSignSubmission.STALE_DROPPED
        acceptedGeneration = generation
        overlay.hide()
        Log.d(TAG, "hide gen=$generation reason=${frame.clearReason}")
        SpeedSignSubmission.ACCEPTED
    }

    override fun close() {
        overlay.close()
    }

    /** Test-only: current accepted generation for assertions. */
    internal fun acceptedGenerationForTest(): Long = synchronized(lock) { acceptedGeneration }
}
