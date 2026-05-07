package com.networksimulator.vpn

import android.util.Log
import com.networksimulator.model.NetworkProfile
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val TAG = "PacketProcessor"

/**
 * Stateless (per-call) simulation engine.
 *
 * Three orthogonal behaviours are implemented:
 *
 *  1. **Packet loss** — [shouldDrop] returns `true` with probability equal to
 *     [NetworkProfile.packetLossPercent].
 *
 *  2. **Latency + jitter** — [applyLatency] suspends the calling coroutine for
 *     `latencyMs ± jitterMs` milliseconds before the packet is forwarded.
 *
 *  3. **Bandwidth throttle** — [applyBandwidthThrottle] accumulates bytes sent
 *     in 1-second windows and suspends the coroutine when the configured
 *     [NetworkProfile.bandwidthKbps] ceiling is reached.
 *
 * Thread-safety: the throttle state is **not** thread-safe by design.  Each
 * [PacketProcessor] instance should be owned by a single coroutine/thread.
 * The VPN service creates one instance and calls it from a single IO coroutine.
 */
class PacketProcessor(profile: NetworkProfile) {

    // ── Mutable profile (hot-swap during a live VPN session) ─────────────────

    @Volatile
    var profile: NetworkProfile = profile
        set(value) {
            field = value
            resetThrottle()
        }

    // ── Throttle state ────────────────────────────────────────────────────────

    private var bytesSentThisWindow = 0L
    private var windowStartMs       = System.currentTimeMillis()

    private fun resetThrottle() {
        bytesSentThisWindow = 0L
        windowStartMs       = System.currentTimeMillis()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns `true` when the packet should be silently dropped.
     * Fast path: skips RNG when loss is configured as 0 %.
     */
    fun shouldDrop(): Boolean {
        val loss = profile.packetLossPercent
        if (loss <= 0f) return false
        val dropped = Random.nextFloat() * 100f < loss
        if (dropped) Log.v(TAG, "Packet dropped (loss=${loss}%)")
        return dropped
    }

    /**
     * Suspends for `latencyMs ± jitterMs` before the packet is forwarded.
     * Jitter is a uniform random variable in [-jitterMs, +jitterMs].
     */
    suspend fun applyLatency() {
        val base   = profile.latencyMs
        val jitter = profile.jitterMs
        if (base <= 0L && jitter <= 0L) return

        val variance  = if (jitter > 0) Random.nextLong(-jitter, jitter + 1) else 0L
        val totalMs   = (base + variance).coerceAtLeast(0L)
        if (totalMs > 0) {
            Log.v(TAG, "Latency delay: ${totalMs}ms (base=${base}, jitter=${variance})")
            delay(totalMs)
        }
    }

    /**
     * Throttles throughput to [NetworkProfile.bandwidthKbps] kilobits per second
     * using a simple 1-second sliding window.
     *
     * @param byteCount number of bytes in the packet just forwarded.
     */
    suspend fun applyBandwidthThrottle(byteCount: Int) {
        val kbps = profile.bandwidthKbps
        if (kbps <= 0) return                               // unlimited

        val maxBytesPerSecond = (kbps * 1024L) / 8L        // kbps → bytes/s

        val now     = System.currentTimeMillis()
        val elapsed = now - windowStartMs

        if (elapsed >= 1_000L) {
            // New window
            bytesSentThisWindow = byteCount.toLong()
            windowStartMs       = now
        } else {
            bytesSentThisWindow += byteCount
            if (bytesSentThisWindow > maxBytesPerSecond) {
                val waitMs = 1_000L - elapsed
                Log.d(TAG, "Throttle: quota exhausted (${bytesSentThisWindow}B > ${maxBytesPerSecond}B/s), sleeping ${waitMs}ms")
                delay(waitMs.coerceAtLeast(1L))
                bytesSentThisWindow = 0L
                windowStartMs       = System.currentTimeMillis()
            }
        }
    }
}
