package com.networksimulator.stats

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One second's worth of network simulation telemetry.
 *
 * @param timestampMs       Wall-clock time this snapshot was taken.
 * @param bytesFwd          Total bytes forwarded to the real internet in this window.
 * @param packetsTotal      Total packets processed (before drop gate).
 * @param packetsDropped    Packets silently dropped this window.
 * @param avgLatencyMs      Average latency applied this window (weighted by packet count).
 * @param screenLabel       Optional user-supplied label (e.g. "Login screen", "OTP screen").
 *                          Non-null only on the snapshot that was manually marked.
 */
data class StatSnapshot(
    val timestampMs:    Long,
    val bytesFwd:       Long,
    val packetsTotal:   Int,
    val packetsDropped: Int,
    val avgLatencyMs:   Long,
    val screenLabel:    String? = null
) {
    // ── Derived helpers ───────────────────────────────────────────────────────

    /** Throughput in kbps for the 1-second window. */
    val throughputKbps: Int get() = ((bytesFwd * 8) / 1024).toInt()

    /** Loss percentage for this window (0–100, one decimal). */
    val lossPercent: Float
        get() = if (packetsTotal == 0) 0f
                else (packetsDropped.toFloat() / packetsTotal) * 100f

    /** Human-readable timestamp for display. */
    val timeLabel: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(timestampMs))

    /** CSV row: timestamp,throughput_kbps,loss_%,avg_latency_ms,packets_total,screen_label */
    fun toCsvRow(): String {
        val label = screenLabel?.replace(",", ";") ?: ""
        return "$timeLabel,$throughputKbps,${String.format("%.1f", lossPercent)},$avgLatencyMs,$packetsTotal,$label"
    }

    companion object {
        const val CSV_HEADER =
            "time,throughput_kbps,loss_percent,avg_latency_ms,packets_total,screen_label"
    }
}
