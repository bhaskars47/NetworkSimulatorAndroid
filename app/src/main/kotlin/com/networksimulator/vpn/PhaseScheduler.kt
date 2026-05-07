package com.networksimulator.vpn

import android.util.Log
import com.networksimulator.model.NetworkProfile
import com.networksimulator.model.ProfileType
import com.networksimulator.stats.StatsCollector
import kotlinx.coroutines.*

private const val TAG = "PhaseScheduler"

/**
 * Cycles the VPN [PacketProcessor] through a realistic sequence of network
 * conditions, averaging the real-world experience of a person moving through
 * everyday India — commuting on a train, stepping into a lift, sitting in a
 * stadium, or being Bharat Aunty in a tier-2 city.
 *
 * Three phases loop indefinitely:
 *
 *  1. **Good Signal** (30 s) — 10 000 kbps, 60 ms latency, 0 % loss.
 *     Typical Jio 4G in decent coverage: home, open road, station platform.
 *
 *  2. **Signal Dip** (10 s) — 600 kbps, 250 ms latency, 8 % loss.
 *     Real degradation: moving auto, semi-covered building, congested tower
 *     (stadium / busy market), semi-urban outskirts.
 *
 *  3. **Dead Zone** (5 s) — 80 kbps, 500 ms latency, 65 % loss.
 *     Lift, basement, tunnel, rural dead stretch — TCP survives but data stalls.
 *
 * When a phase changes the scheduler:
 *  - Hot-swaps [PacketProcessor.profile] (thread-safe via @Volatile setter)
 *  - Stamps [StatsCollector.pendingScreenLabel] so the log shows the transition
 *  - Fires the [onPhaseChanged] callback so the UI can display the current phase
 *
 * Usage:
 * ```
 *   val scheduler = PhaseScheduler()
 *   scheduler.start(serviceScope, processor, stats) { label -> /* update UI */ }
 *   // when VPN stops:
 *   scheduler.stop()
 * ```
 */
class PhaseScheduler {

    // ── Phase definitions ─────────────────────────────────────────────────────

    private data class TrainPhase(
        val label:           String,
        val profile:         NetworkProfile,
        val durationSeconds: Int
    )

    private val phases: List<TrainPhase> = listOf(
        TrainPhase(
            // Typical Jio 4G in good coverage — home, platform, open road
            label           = "Good signal",
            profile         = NetworkProfile(
                type                = ProfileType.TRAIN,
                name                = "Real India — Good Signal",
                description         = "Typical 4G in decent coverage",
                latencyMs           = 60L,
                jitterMs            = 20L,
                packetLossPercent   = 0f,     // clean baseline — no artificial loss in good signal
                bandwidthKbps       = 10_000  // ~10 Mbps — Jio 4G average
            ),
            durationSeconds = 30
        ),
        TrainPhase(
            // Coverage dip — moving auto, semi-covered area, congested tower
            label           = "Signal dip",
            profile         = NetworkProfile(
                type                = ProfileType.TRAIN,
                name                = "Real India — Signal Dip",
                description         = "Weak coverage or congested tower",
                latencyMs           = 250L,
                jitterMs            = 80L,
                packetLossPercent   = 8f,
                bandwidthKbps       = 600     // ~600 kbps — real degraded 4G
            ),
            durationSeconds = 10
        ),
        TrainPhase(
            // Dead zone — lift, basement, tunnel, rural gap
            // 80 kbps lets TCP keepalives survive (realistic — you don't fully drop)
            label           = "Dead zone",
            profile         = NetworkProfile(
                type                = ProfileType.TRAIN,
                name                = "Real India — Dead Zone",
                description         = "Lift, tunnel or rural dead stretch",
                latencyMs           = 500L,
                jitterMs            = 100L,
                packetLossPercent   = 65f,
                bandwidthKbps       = 80      // ~80 kbps — near-zero but TCP survives
            ),
            durationSeconds = 5
        )
    )

    // ── Mutable state ─────────────────────────────────────────────────────────

    /** Current phase label — readable from any thread. */
    @Volatile var currentPhaseLabel: String = "—"
        private set

    private var job: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts the phase-cycling loop inside [scope].
     *
     * @param scope          Coroutine scope tied to the VPN service lifetime.
     * @param processor      Packet processor whose profile will be hot-swapped.
     * @param stats          Stats collector used for phase-transition labels.
     * @param onPhaseChanged Called on each phase transition with the new label.
     */
    fun start(
        scope:          CoroutineScope,
        processor:      PacketProcessor,
        stats:          StatsCollector,
        onPhaseChanged: (String) -> Unit = {}
    ) {
        job?.cancel()
        job = scope.launch {
            Log.i(TAG, "PhaseScheduler started — ${phases.size} phases, looping indefinitely")
            var index = 0
            while (isActive) {
                val phase = phases[index % phases.size]
                Log.i(TAG, "Phase ${index % phases.size + 1}/${phases.size}: '${phase.label}' (${phase.durationSeconds}s)")

                // Stamp this phase as a screen label in the next stats flush
                stats.pendingScreenLabel = phase.label

                // Hot-swap the profile — @Volatile setter resets the throttle bucket
                processor.profile = phase.profile

                // Notify observers
                currentPhaseLabel = phase.label
                onPhaseChanged(phase.label)

                delay(phase.durationSeconds * 1_000L)
                index++
            }
            Log.i(TAG, "PhaseScheduler loop ended")
        }
    }

    /**
     * Cancels the cycling loop.  Safe to call more than once.
     * Does NOT touch [PacketProcessor.profile] — the service will stop the VPN anyway.
     */
    fun stop() {
        job?.cancel()
        job = null
        currentPhaseLabel = "—"
        Log.i(TAG, "PhaseScheduler stopped")
    }
}
