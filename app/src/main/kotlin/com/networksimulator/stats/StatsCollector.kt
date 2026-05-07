package com.networksimulator.stats

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "StatsCollector"

/**
 * Singleton stats accumulator — ONE instance for the entire app lifetime.
 *
 * Previously a new instance was created each time the VPN started, causing a
 * race condition where the ViewModel held a reference to the old instance.
 * Now it is a stable singleton; [reset] clears counters between sessions.
 */
class StatsCollector {

    @Volatile private var bytesFwd:       Long = 0L
    @Volatile private var packetsTotal:   Int  = 0
    @Volatile private var packetsDropped: Int  = 0
    @Volatile private var latencySum:     Long = 0L
    @Volatile private var latencyCount:   Int  = 0

    @Volatile var pendingScreenLabel: String? = null

    // replay=300  → a ViewModel that restarts mid-session (activity recreated when
    //               returning from another app) immediately receives the last 5 min of
    //               history instead of an empty log.
    // extraBufferCapacity=300 → tryEmit() never drops a snapshot even when the
    //               collector is temporarily slow.
    private val _snapshots = MutableSharedFlow<StatSnapshot>(
        replay             = 300,
        extraBufferCapacity = 300
    )
    val snapshots = _snapshots.asSharedFlow()

    // ── Hot-path ──────────────────────────────────────────────────────────────

    fun recordForwarded(bytes: Int) { bytesFwd += bytes; packetsTotal += 1 }
    fun recordDropped()             { packetsTotal += 1; packetsDropped += 1 }
    fun recordLatency(ms: Long)     { if (ms > 0) { latencySum += ms; latencyCount += 1 } }

    /** Call when VPN session starts — clears all counters and log. */
    @Synchronized
    fun reset() {
        bytesFwd = 0L; packetsTotal = 0; packetsDropped = 0
        latencySum = 0L; latencyCount = 0; pendingScreenLabel = null
        Log.d(TAG, "Stats reset for new session")
    }

    /** Called once per second by the VPN service tick coroutine. */
    @Synchronized
    fun flush(): StatSnapshot {
        val avgLatency = if (latencyCount > 0) latencySum / latencyCount else 0L
        val label      = pendingScreenLabel.also { pendingScreenLabel = null }

        val snap = StatSnapshot(
            timestampMs    = System.currentTimeMillis(),
            bytesFwd       = bytesFwd,
            packetsTotal   = packetsTotal,
            packetsDropped = packetsDropped,
            avgLatencyMs   = avgLatency,
            screenLabel    = label
        )

        bytesFwd = 0L; packetsTotal = 0; packetsDropped = 0
        latencySum = 0L; latencyCount = 0

        Log.d(TAG, "Flush: ${snap.throughputKbps} kbps | ${snap.lossPercent}% loss | ${avgLatency}ms latency | packets=${snap.packetsTotal}")
        _snapshots.tryEmit(snap)
        return snap
    }
}
