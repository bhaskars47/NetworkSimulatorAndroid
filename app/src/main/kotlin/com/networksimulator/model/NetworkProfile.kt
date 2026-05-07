package com.networksimulator.model

import java.io.Serializable

/**
 * Identifies which preset (or custom) profile is active.
 */
enum class ProfileType {
    SLOW_3G,
    HIGH_LATENCY,
    PACKET_LOSS,
    CUSTOM,
    /** Dynamic multi-phase profile — simulates a moving train journey. */
    TRAIN
}

/**
 * Immutable description of a simulated network environment.
 *
 * @param type            Which preset this profile represents.
 * @param name            Human-readable label shown in the UI.
 * @param description     Short one-liner shown under the card title.
 * @param latencyMs       Fixed added delay per outbound packet (milliseconds).
 * @param jitterMs        ± random variance applied on top of [latencyMs].
 * @param packetLossPercent  Probability (0–100 %) that a packet is silently dropped.
 * @param bandwidthKbps   Maximum throughput in kilobits-per-second. -1 = unlimited.
 */
data class NetworkProfile(
    val type: ProfileType,
    val name: String,
    val description: String,
    val latencyMs: Long,
    val jitterMs: Long,
    val packetLossPercent: Float,   // 0f – 100f
    val bandwidthKbps: Int          // -1  = no throttle
) : Serializable {

    companion object {

        fun slow3G() = NetworkProfile(
            type                = ProfileType.SLOW_3G,
            name                = "Slow 3G",
            description         = "~300 kbps · 300 ms latency · 1 % loss",
            latencyMs           = 300L,
            jitterMs            = 50L,
            packetLossPercent   = 1f,
            bandwidthKbps       = 300
        )

        fun highLatency() = NetworkProfile(
            type                = ProfileType.HIGH_LATENCY,
            name                = "High Latency",
            description         = "Unlimited speed · 1 000 ms latency · 0.5 % loss",
            latencyMs           = 1_000L,
            jitterMs            = 200L,
            packetLossPercent   = 0.5f,
            bandwidthKbps       = -1
        )

        fun packetLoss() = NetworkProfile(
            type                = ProfileType.PACKET_LOSS,
            name                = "Packet Loss",
            description         = "20 % random drop · 50 ms latency",
            latencyMs           = 50L,
            jitterMs            = 10L,
            packetLossPercent   = 20f,
            bandwidthKbps       = -1
        )

        /**
         * Train / Moving Vehicle profile — used as the display card entry.
         * The actual per-phase profiles are defined inside [com.networksimulator.vpn.PhaseScheduler]
         * and are hot-swapped by it at runtime.  This profile represents the first phase
         * (Good Signal) so that the simulator starts in the best-case state.
         */
        fun train() = NetworkProfile(
            type                = ProfileType.TRAIN,
            name                = "Real India",
            description         = "Cycles: good signal → signal dip → dead zone",
            latencyMs           = 60L,
            jitterMs            = 20L,
            packetLossPercent   = 0f,
            bandwidthKbps       = 10_000  // initial phase — Good Signal (Jio 4G average)
        )

        /**
         * Custom profile with user-supplied values.
         * All parameters have reasonable defaults so callers only override what they care about.
         */
        fun custom(
            latencyMs:          Long  = 100L,
            jitterMs:           Long  = 20L,
            packetLossPercent:  Float = 5f,
            bandwidthKbps:      Int   = -1
        ) = NetworkProfile(
            type                = ProfileType.CUSTOM,
            name                = "Custom",
            description         = "User-defined latency / loss / bandwidth",
            latencyMs           = latencyMs,
            jitterMs            = jitterMs,
            packetLossPercent   = packetLossPercent,
            bandwidthKbps       = bandwidthKbps
        )

        /** All profiles in display order. */
        fun allProfiles(): List<NetworkProfile> =
            listOf(slow3G(), highLatency(), packetLoss(), custom(), train())
    }
}
