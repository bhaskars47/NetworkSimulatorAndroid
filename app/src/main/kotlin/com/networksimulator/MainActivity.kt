package com.networksimulator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
import com.networksimulator.databinding.ActivityMainBinding
import com.networksimulator.model.NetworkProfile
import com.networksimulator.model.ProfileType
import com.networksimulator.stats.StatSnapshot
import com.networksimulator.ui.MainViewModel
import com.networksimulator.ui.StatsLogAdapter
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private val adapter = StatsLogAdapter()

    // ── VPN permission flow ───────────────────────────────────────────────────

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.onVpnPermissionGranted()
        else Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
    }

    // ── Notification permission (Android 13+) ────────────────────────────────

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notification permission denied — live stats won't appear in the notification shade",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setupProfileCards()
        setupCustomPanel()
        setupTargetPackageInput()
        setupToggleButton()
        setupStatsPanel()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshRunningState()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupProfileCards() {
        mapOf(
            binding.cardSlow3g      to ProfileType.SLOW_3G,
            binding.cardHighLatency to ProfileType.HIGH_LATENCY,
            binding.cardPacketLoss  to ProfileType.PACKET_LOSS,
            binding.cardCustom      to ProfileType.CUSTOM,
            binding.cardTrain       to ProfileType.TRAIN
        ).forEach { (card, type) -> card.setOnClickListener { vm.selectProfile(type) } }
    }

    private fun setupCustomPanel() {
        binding.seekLatency.max = 200
        binding.seekLatency.setOnSeekBarChangeListener(simpleListener { p ->
            val ms = p * 10L
            binding.labelLatencyValue.text = "$ms ms"
            vm.setCustomLatency(ms)
        })

        binding.seekJitter.max = 50
        binding.seekJitter.setOnSeekBarChangeListener(simpleListener { p ->
            val ms = p * 10L
            binding.labelJitterValue.text = "± $ms ms"
            vm.setCustomJitter(ms)
        })

        binding.seekLoss.max = 100
        binding.seekLoss.setOnSeekBarChangeListener(simpleListener { p ->
            binding.labelLossValue.text = "$p %"
            vm.setCustomLoss(p.toFloat())
        })

        binding.seekBandwidth.max = 200
        binding.seekBandwidth.setOnSeekBarChangeListener(simpleListener { p ->
            val kbps = if (p == 0) -1 else p * 10
            binding.labelBandwidthValue.text = if (kbps < 0) "Unlimited" else "$kbps kbps"
            vm.setCustomBandwidth(kbps)
        })
    }

    private fun setupTargetPackageInput() {
        binding.editTargetPackage.doAfterTextChanged { vm.setTargetPackage(it?.toString() ?: "") }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupToggleButton() {
        binding.btnToggle.setOnClickListener {
            val isRunning = vm.isRunning.value ?: false
            val hasOldData = (vm.statsLog.value?.size ?: 0) > 0

            // Warn if there's existing log data before starting a new session
            if (!isRunning && hasOldData) {
                AlertDialog.Builder(this)
                    .setTitle("Log has data from a previous session")
                    .setMessage("Starting now will mix old and new data in your report. Clear the log first for a clean result.")
                    .setPositiveButton("Clear & Start") { _, _ ->
                        vm.clearLog()
                        adapter.submitList(emptyList())
                        vm.toggleVpn(VpnService.prepare(this))
                    }
                    .setNeutralButton("Start Anyway") { _, _ ->
                        vm.toggleVpn(VpnService.prepare(this))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                vm.toggleVpn(VpnService.prepare(this))
            }
        }
    }

    private fun setupStatsPanel() {
        // RecyclerView
        binding.rvStatsLog.layoutManager = LinearLayoutManager(this)
        binding.rvStatsLog.adapter       = adapter

        // Mark screen button — shows a dialog to name the current screen
        binding.btnMarkScreen.setOnClickListener {
            val input = EditText(this).apply {
                hint = "e.g.  Login screen,  OTP screen,  Home"
                setPadding(48, 24, 48, 12)
            }
            AlertDialog.Builder(this)
                .setTitle("Mark current screen")
                .setView(input)
                .setPositiveButton("Mark") { _, _ ->
                    val label = input.text.toString().trim()
                    if (label.isNotEmpty()) {
                        vm.markScreen(label)
                        Toast.makeText(this, "Marked: $label", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Export log — show format picker
        binding.btnExportCsv.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Export session log")
                .setItems(arrayOf("CSV  (spreadsheet)", "HTML  (visual report)")) { _, which ->
                    if (which == 0) exportCsv() else exportHtml()
                }
                .show()
        }

        // Clear log
        binding.btnClearLog.setOnClickListener {
            vm.clearLog()
            adapter.submitList(emptyList())
        }
    }

    // ── Observation ───────────────────────────────────────────────────────────

    private fun observeViewModel() {
        vm.isRunning.observe(this) { running -> updateRunningUi(running) }

        vm.selectedProfile.observe(this) { profile ->
            highlightCard(profile.type)
            binding.panelCustom.visibility =
                if (profile.type == ProfileType.CUSTOM) View.VISIBLE else View.GONE
        }

        vm.currentPhaseLabel.observe(this) { label ->
            val isTrainRunning = vm.selectedProfile.value?.type == ProfileType.TRAIN &&
                                 vm.isRunning.value == true
            // Update the phase indicator banner in the stats panel
            binding.phaseIndicatorCard.visibility = if (isTrainRunning) View.VISIBLE else View.GONE
            binding.tvPhaseLabel.text = label
            // Update the inline chip on the Train card
            binding.tvTrainPhaseChip.text = label
            binding.tvTrainPhaseChip.visibility = if (isTrainRunning) View.VISIBLE else View.GONE
        }

        vm.latestSnapshot.observe(this) { snap ->
            if (snap != null) updateLiveStats(snap)
        }

        vm.statsLog.observe(this) { log ->
            adapter.submitList(log)
            if (log.isNotEmpty()) binding.rvStatsLog.scrollToPosition(0)
        }

        vm.event.observe(this) { event ->
            when (event) {
                is MainViewModel.UiEvent.RequestVpnPermission -> {
                    vpnPermissionLauncher.launch(event.prepareIntent); vm.consumeEvent()
                }
                is MainViewModel.UiEvent.VpnStarted -> {
                    Toast.makeText(this, "Simulation started", Toast.LENGTH_SHORT).show()
                    vm.consumeEvent()
                }
                is MainViewModel.UiEvent.VpnStopped -> {
                    Toast.makeText(this, "Simulation stopped", Toast.LENGTH_SHORT).show()
                    vm.consumeEvent()
                }
                null -> {}
            }
        }
    }

    // ── UI state helpers ──────────────────────────────────────────────────────

    private fun updateRunningUi(running: Boolean) {
        binding.btnToggle.text        = if (running) "Stop Simulation" else "Start Simulation"
        binding.statusChip.text       = if (running) "Active" else "Inactive"
        binding.statusChip.isSelected = running
        val alpha = if (running) 0.5f else 1.0f
        listOf(binding.cardSlow3g, binding.cardHighLatency,
               binding.cardPacketLoss, binding.cardCustom, binding.cardTrain,
               binding.panelCustom).forEach { it.alpha = alpha }
        binding.editTargetPackage.isEnabled = !running
        binding.btnMarkScreen.isEnabled     = running
        binding.statsPanel.visibility = View.VISIBLE

        // Show/hide phase indicator based on selected profile + running state
        val isTrainRunning = running && vm.selectedProfile.value?.type == ProfileType.TRAIN
        binding.phaseIndicatorCard.visibility = if (isTrainRunning) View.VISIBLE else View.GONE
        binding.tvTrainPhaseChip.visibility   = if (isTrainRunning) View.VISIBLE else View.GONE
    }

    private fun updateLiveStats(snap: StatSnapshot) {
        binding.tvLiveSpeed.text    = "${snap.throughputKbps} kbps"
        binding.tvLiveLatency.text  = "${snap.avgLatencyMs} ms"
        binding.tvLiveLoss.text     = "${String.format("%.1f", snap.lossPercent)} %"
    }

    private fun highlightCard(type: ProfileType) {
        mapOf(
            ProfileType.SLOW_3G      to binding.cardSlow3g,
            ProfileType.HIGH_LATENCY to binding.cardHighLatency,
            ProfileType.PACKET_LOSS  to binding.cardPacketLoss,
            ProfileType.CUSTOM       to binding.cardCustom,
            ProfileType.TRAIN        to binding.cardTrain
        ).forEach { (t, card) -> card.strokeWidth = if (t == type) 4 else 0 }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun exportCsv() {
        val log = (vm.statsLog.value ?: emptyList()).reversed()
        if (log.isEmpty()) { Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show(); return }
        try {
            val sb = StringBuilder().appendLine(StatSnapshot.CSV_HEADER)
            log.forEach { sb.appendLine(it.toCsvRow()) }
            val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = "network_sim_$ts.csv"
            saveToDocuments(name, "text/csv", sb.toString())
            Toast.makeText(this, "Saved to Documents/NetworkSimulator/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportHtml() {
        val log = (vm.statsLog.value ?: emptyList()).reversed()
        if (log.isEmpty()) { Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show(); return }
        try {
            val ts      = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name    = "network_sim_$ts.html"
            val profile = vm.selectedProfile.value
            val target  = vm.targetPackage.value ?: ""
            saveToDocuments(name, "text/html", buildHtmlReport(log, profile, target))
            Toast.makeText(this, "Saved to Documents/NetworkSimulator/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves [content] to /sdcard/Documents/NetworkSimulator/[fileName] via MediaStore (Android 10+). */
    private fun saveToDocuments(fileName: String, mimeType: String, content: String) {
        val resolver  = contentResolver
        val relPath   = "Documents/NetworkSimulator"
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // Delete any prior file with the same name so we always overwrite cleanly
        resolver.delete(collection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("$relPath%", fileName))

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE,    mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        }
        val uri = resolver.insert(collection, values)
            ?: throw Exception("MediaStore could not create $fileName")
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    // ── HTML report builder ───────────────────────────────────────────────────

    private fun buildHtmlReport(
        log:     List<StatSnapshot>,
        profile: NetworkProfile?,
        target:  String
    ): String {
        // ── Summary stats ──────────────────────────────────────────────────────
        val active   = log.filter { it.packetsTotal > 0 }
        val avgLat   = active.filter { it.avgLatencyMs > 0 }.let {
            if (it.isEmpty()) 0L else it.sumOf { s -> s.avgLatencyMs } / it.size
        }
        val peakTp   = log.maxOfOrNull { it.throughputKbps } ?: 0
        val totalPkt = log.sumOf { it.packetsTotal }
        val dropPkt  = log.sumOf { it.packetsDropped }
        val lossEvts = log.count { it.lossPercent > 0f }
        val overallLoss = if (totalPkt == 0) 0f
                          else (dropPkt.toFloat() / (totalPkt + dropPkt)) * 100f

        val sessionTs = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                            .format(Date(log.firstOrNull()?.timestampMs ?: System.currentTimeMillis()))

        // ── Simulation config rows ─────────────────────────────────────────────
        val isTrain   = profile?.type == ProfileType.TRAIN
        val profName  = profile?.name ?: "Unknown"
        val cfgLat    = if (isTrain) "Dynamic (phase-based)"
                        else profile?.let { "${it.latencyMs} ms ± ${it.jitterMs} ms" } ?: "—"
        val cfgLoss   = if (isTrain) "Dynamic (phase-based)"
                        else profile?.let { "${"%.1f".format(it.packetLossPercent)} %" } ?: "—"
        val cfgBw     = if (isTrain) "Dynamic (phase-based)"
                        else profile?.let {
                            if (it.bandwidthKbps < 0) "Unlimited" else "${it.bandwidthKbps} kbps"
                        } ?: "—"
        val cfgTarget = target.ifBlank { "All apps (no filter)" }
        val cfgTrainPhases = if (isTrain) """
    <div class="cfg-item" style="grid-column:1/-1">
      <div class="cfg-lbl">Phase cycle (covers: Train · Lift · Stadium · Tier-2 · Bharat Aunty)</div>
      <div class="cfg-val" style="font-size:12px;font-weight:400">
        Good Signal (30 s): 60 ms · 10 000 kbps · 0% loss &nbsp;→&nbsp;
        Signal Dip (10 s): 250 ms · 600 kbps · 8% loss &nbsp;→&nbsp;
        Dead Zone (5 s): 500 ms · 80 kbps · 65% loss &nbsp;→&nbsp; repeat
      </div>
    </div>""" else ""

        // ── Interpretation paragraph ───────────────────────────────────────────
        val latDelta  = profile?.let { avgLat - it.latencyMs } ?: 0L
        val latNote   = when {
            isTrain                     -> "This session used the <b>Real India</b> profile, simulating the realistic network experience of everyday users — train commuters, Bharat Aunty in a tier-2 city, someone stepping into a lift, or standing in a crowded stadium. Three phases cycled automatically: <b>Good Signal</b> (60 ms / 10 000 kbps / 0% loss, 30 s), <b>Signal Dip</b> (250 ms / 600 kbps / 8% loss, 10 s), and <b>Dead Zone</b> (500 ms / 80 kbps / 65% loss, 5 s). Look for the phase labels in the <b>Time</b> column to identify which condition caused each observed pattern. Overall average latency across all phases was <b>${avgLat} ms</b>."
            avgLat == 0L                -> "No active-traffic latency was recorded."
            profile == null             -> "Average observed latency was <b>${avgLat} ms</b>."
            latDelta > profile.latencyMs -> "Average observed latency (<b>${avgLat} ms</b>) was significantly higher than the configured base (${profile.latencyMs} ms). This often means the log contains data from a previous session — use <b>Clear Log</b> before each test run to avoid mixing sessions."
            latDelta < 0                -> "Observed average latency (<b>${avgLat} ms</b>) was below the configured base — this can happen when most traffic was idle ACKs which are not latency-tracked."
            else                        -> "Observed average latency (<b>${avgLat} ms</b>) is consistent with the configured base of ${profile.latencyMs} ms (within expected jitter of ±${profile.jitterMs} ms)."
        }
        val lossNote  = when {
            isTrain && lossEvts > 0     -> "<b>${lossEvts} seconds</b> had packet drops — expected during Handover and Dead Zone phases. Check whether the app surfaced errors or retried silently."
            lossEvts == 0               -> "No packet loss was recorded during this session — the app handled all simulated conditions gracefully."
            profile != null && profile.packetLossPercent >= 15f ->
                "<b>${lossEvts} seconds</b> had packet drops. This is expected under the '${profName}' profile (${"%.0f".format(profile.packetLossPercent)}% loss rate). Check whether the app surfaced an error to the user."
            else                        -> "<b>${lossEvts} second(s)</b> experienced packet drops. Overall effective loss rate: ${"%.1f".format(overallLoss)}%."
        }
        val tpNote    = when {
            peakTp == 0                 -> "No data bytes were forwarded — the session may have only captured connection setup packets."
            isTrain                     -> "Peak throughput was <b>${peakTp} kbps</b>. Throughput varies across phases: up to 10 000 kbps during Good Signal, ~600 kbps during Signal Dip, and ~80 kbps during Dead Zone."
            profile != null && profile.bandwidthKbps > 0 && peakTp > profile.bandwidthKbps ->
                "Peak throughput (<b>${peakTp} kbps</b>) exceeded the configured cap (${profile.bandwidthKbps} kbps). This is normal — the cap is a token-bucket average, not a hard ceiling every second."
            else                        -> "Peak throughput was <b>${peakTp} kbps</b>."
        }

        // ── JS data arrays ─────────────────────────────────────────────────────
        val labels  = log.joinToString(",") { "\"${it.timeLabel}\"" }
        val tpArr   = log.joinToString(",") { it.throughputKbps.toString() }
        // Emit null for idle seconds (no packets) AND for seconds where every packet
        // was dropped (avgLatencyMs == 0 because latencyCount stayed at 0).
        // Without this, 100%-loss seconds emit "0" and drag the latency line to the floor.
        val latArr  = log.joinToString(",") {
            if (it.packetsTotal == 0 || it.avgLatencyMs == 0L) "null" else it.avgLatencyMs.toString()
        }

        // Chart Y-axis max: round up to the next 200 ms above peak observed latency,
        // but always at least 400 ms so the axis has breathing room.
        val peakLat   = log.maxOfOrNull { if (it.packetsTotal > 0) it.avgLatencyMs else 0L } ?: 0L
        val latYMax   = maxOf(400L, ((peakLat / 200L) + 1L) * 200L)

        // ── Per-second table rows ──────────────────────────────────────────────
        // Warn threshold: configured latency + jitter, or 400 ms floor.
        // For the Train profile the factory values (60 ms + 20 ms) would floor to 400 ms,
        // which is too high — Handover rows at ~350 ms would never be flagged.
        // Use 200 ms for Train so any second exceeding Good-Signal range turns yellow.
        val warnLatMs = when {
            isTrain -> 200L
            else    -> maxOf(400L, (profile?.let { it.latencyMs + it.jitterMs } ?: 400L))
        }
        val rows = log.joinToString("\n") { s ->
            val rowClass = when {
                s.packetsTotal == 0        -> "idle"
                s.lossPercent  > 0f        -> "bad"
                s.avgLatencyMs > warnLatMs -> "warn"
                else                       -> "ok"
            }
            val lossCell = if (s.lossPercent == 0f) "—"
                           else "<span class=\"badge-loss\">${"%.1f".format(s.lossPercent)}%</span>"
            val screen   = s.screenLabel?.let { " <span class=\"screen-tag\">$it</span>" } ?: ""
            """<tr class="$rowClass">
              <td>${s.timeLabel}$screen</td>
              <td>${if (s.throughputKbps > 0) "${s.throughputKbps} kbps" else "—"}</td>
              <td>${if (s.avgLatencyMs  > 0) "${s.avgLatencyMs} ms"  else "—"}</td>
              <td>${if (s.packetsTotal  > 0) s.packetsTotal.toString() else "—"}</td>
              <td>$lossCell</td>
            </tr>"""
        }

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Network Sim Report — $sessionTs</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;font-size:14px;color:#1a1a1a;background:#f5f5f0;padding:20px}
  h1{font-size:20px;font-weight:700;margin-bottom:4px}
  .sub{color:#666;font-size:13px;margin-bottom:20px}
  /* Config block */
  .config-box{background:#fff;border-radius:10px;border:1px solid #e5e5e5;padding:16px 20px;margin-bottom:24px}
  .config-title{font-size:12px;font-weight:700;color:#555;text-transform:uppercase;letter-spacing:.06em;margin-bottom:12px}
  .config-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:10px}
  .cfg-item{background:#f8f8f6;border-radius:7px;padding:10px 12px}
  .cfg-lbl{font-size:10px;color:#999;text-transform:uppercase;letter-spacing:.04em;margin-bottom:3px}
  .cfg-val{font-size:14px;font-weight:600;color:#222;word-break:break-all}
  /* Summary cards */
  .cards{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:24px}
  .card{background:#fff;border-radius:10px;padding:14px 16px;border:1px solid #e5e5e5}
  .card-lbl{font-size:11px;color:#888;margin-bottom:4px;text-transform:uppercase;letter-spacing:.04em}
  .card-val{font-size:24px;font-weight:700;color:#111}
  .card-sub{font-size:11px;color:#aaa;margin-top:2px}
  /* Section headings */
  .section{font-size:12px;font-weight:700;color:#888;text-transform:uppercase;letter-spacing:.06em;margin:0 0 10px}
  /* Chart */
  .chart-box{background:#fff;border-radius:10px;border:1px solid #e5e5e5;padding:20px;margin-bottom:24px}
  .chart-wrap{position:relative;height:220px}
  .chart-legend{display:flex;gap:20px;margin-bottom:14px;font-size:12px;color:#555}
  .legend-dot{width:10px;height:10px;border-radius:2px;display:inline-block;margin-right:5px;vertical-align:middle}
  /* Explainer box */
  .explain-box{background:#fff;border-radius:10px;border:1px solid #e5e5e5;padding:16px 20px;margin-bottom:24px;font-size:13px;line-height:1.6;color:#444}
  .explain-box h3{font-size:12px;font-weight:700;color:#555;text-transform:uppercase;letter-spacing:.06em;margin-bottom:10px}
  .explain-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-bottom:14px}
  .explain-item{background:#f8f8f6;border-radius:7px;padding:10px 12px}
  .explain-item strong{display:block;font-size:12px;color:#333;margin-bottom:4px}
  .interpret{background:#fffbf0;border:1px solid #fed7aa;border-radius:7px;padding:12px 14px;font-size:13px;line-height:1.7;color:#7c2d12}
  .interpret h4{font-size:12px;font-weight:700;color:#92400e;margin-bottom:8px;text-transform:uppercase;letter-spacing:.04em}
  /* Table */
  table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden;border:1px solid #e5e5e5;margin-top:0}
  th{background:#fafaf8;font-size:11px;font-weight:600;color:#888;text-transform:uppercase;letter-spacing:.04em;padding:10px 12px;text-align:left;border-bottom:1px solid #e5e5e5}
  td{padding:8px 12px;border-bottom:1px solid #f0f0f0;font-size:13px}
  tr.idle td{color:#bbb;background:#fafafa}
  tr.ok   td{background:#f9fdf4}
  tr.warn td{background:#fffbf0}
  tr.bad  td{background:#fff5f5}
  tr:last-child td{border-bottom:none}
  .badge-loss{background:#fee2e2;color:#991b1b;padding:2px 6px;border-radius:4px;font-size:12px;font-weight:500}
  .screen-tag{background:#e0e7ff;color:#3730a3;padding:1px 6px;border-radius:4px;font-size:11px;margin-left:6px}
  /* Legend row */
  .tbl-legend{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:10px;font-size:12px;color:#555}
  .leg{display:flex;align-items:center;gap:6px}
  .leg-sq{width:12px;height:12px;border-radius:3px;flex-shrink:0}
  @media(max-width:600px){.cards{grid-template-columns:repeat(2,1fr)}.explain-grid{grid-template-columns:1fr}}
</style>
</head>
<body>
<h1>Network Simulation Report</h1>
<p class="sub">$sessionTs &nbsp;·&nbsp; ${log.size} seconds captured</p>

<!-- ── Simulation configuration ─────────────────────────── -->
<div class="config-box">
  <div class="config-title">Simulation configuration</div>
  <div class="config-grid">
    <div class="cfg-item"><div class="cfg-lbl">Profile</div><div class="cfg-val">$profName</div></div>
    <div class="cfg-item"><div class="cfg-lbl">Added latency</div><div class="cfg-val">$cfgLat</div></div>
    <div class="cfg-item"><div class="cfg-lbl">Packet loss</div><div class="cfg-val">$cfgLoss</div></div>
    <div class="cfg-item"><div class="cfg-lbl">Bandwidth cap</div><div class="cfg-val">$cfgBw</div></div>
    <div class="cfg-item"><div class="cfg-lbl">Target app</div><div class="cfg-val">$cfgTarget</div></div>$cfgTrainPhases
  </div>
</div>

<!-- ── Summary cards ────────────────────────────────────── -->
<p class="section">Session summary</p>
<div class="cards">
  <div class="card"><div class="card-lbl">Avg latency</div><div class="card-val">${avgLat} ms</div><div class="card-sub">active seconds only</div></div>
  <div class="card"><div class="card-lbl">Peak throughput</div><div class="card-val">${peakTp} kbps</div><div class="card-sub">per-second peak</div></div>
  <div class="card"><div class="card-lbl">Total packets</div><div class="card-val">$totalPkt</div><div class="card-sub">${dropPkt} dropped</div></div>
  <div class="card"><div class="card-lbl">Loss events</div><div class="card-val">$lossEvts</div><div class="card-sub">seconds with &gt;0% loss</div></div>
</div>

<!-- ── Chart ─────────────────────────────────────────────── -->
<p class="section">Throughput &amp; latency over time</p>
<div class="chart-box">
  <div class="chart-legend">
    <span><span class="legend-dot" style="background:#3b82f6"></span>Throughput (kbps)</span>
    <span><span class="legend-dot" style="background:#ef4444"></span>Latency (ms)</span>
  </div>
  <div class="chart-wrap">
    <canvas id="chart" role="img" aria-label="Throughput and latency chart"></canvas>
  </div>
</div>

<!-- ── Explainer ─────────────────────────────────────────── -->
<div class="explain-box">
  <h3>What the metrics mean</h3>
  <div class="explain-grid">
    <div class="explain-item"><strong>Throughput (kbps)</strong>How many kilobits of data were forwarded to the internet in that second. A low or zero value means the app was idle or waiting — it does not necessarily indicate a problem on its own.</div>
    <div class="explain-item"><strong>Latency (ms)</strong>The artificial delay added to each packet by the simulator, as configured in the profile. High latency (&gt;300 ms) makes interactive actions feel sluggish; above 1 000 ms, timeouts become likely.</div>
    <div class="explain-item"><strong>Packet loss (%)</strong>Percentage of packets randomly dropped in that second. Even 1–2% loss can cause TCP to stall and retry, noticeably slowing the user experience. 10%+ typically causes visible errors or retries.</div>
    <div class="explain-item"><strong>Packets (total)</strong>Number of network packets processed in that second. Zero-packet seconds (shown in gray) are idle windows where the app had no active connections — completely normal between user actions.</div>
  </div>
  <div class="interpret">
    <h4>Session interpretation</h4>
    $latNote<br>$lossNote<br>$tpNote
  </div>
</div>

<!-- ── Per-second table ───────────────────────────────────── -->
<p class="section">Per-second log</p>
<div class="tbl-legend">
  <span class="leg"><span class="leg-sq" style="background:#f9fdf4;border:1px solid #d1fae5"></span>Healthy — normal traffic, latency within range</span>
  <span class="leg"><span class="leg-sq" style="background:#fffbf0;border:1px solid #fde68a"></span>Elevated latency (&gt;${warnLatMs} ms)</span>
  <span class="leg"><span class="leg-sq" style="background:#fff5f5;border:1px solid #fecaca"></span>Packet loss detected</span>
  <span class="leg"><span class="leg-sq" style="background:#fafafa;border:1px solid #e5e5e5"></span>Idle — no packets this second</span>
</div>
<table>
  <thead><tr><th>Time</th><th>Throughput</th><th>Latency</th><th>Packets</th><th>Loss</th></tr></thead>
  <tbody>$rows</tbody>
</table>

<script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.js"></script>
<script>
new Chart(document.getElementById('chart'),{
  data:{
    labels:[$labels],
    datasets:[
      {
        type:'line',label:'Throughput (kbps)',data:[$tpArr],
        borderColor:'#3b82f6',borderWidth:2,
        backgroundColor:'rgba(59,130,246,0.10)',
        fill:true,
        tension:0.4,
        pointRadius:0,pointHoverRadius:4,
        spanGaps:true,
        yAxisID:'yTp',order:2
      },
      {
        type:'line',label:'Latency (ms)',data:[$latArr],
        borderColor:'#ef4444',borderWidth:2,
        backgroundColor:'transparent',
        fill:false,
        tension:0.4,
        pointRadius:0,pointHoverRadius:4,
        spanGaps:false,
        yAxisID:'yLat',order:1
      }
    ]
  },
  options:{
    responsive:true,maintainAspectRatio:false,
    interaction:{mode:'index',intersect:false},
    plugins:{
      legend:{display:false},
      tooltip:{
        callbacks:{
          label:function(ctx){
            return ctx.dataset.label+': '+ctx.parsed.y+(ctx.datasetIndex===0?' kbps':' ms');
          }
        }
      }
    },
    scales:{
      x:{ticks:{font:{size:10},maxRotation:45,autoSkip:true,maxTicksLimit:15},grid:{display:false}},
      yTp:{position:'left',title:{display:true,text:'kbps',font:{size:10}},beginAtZero:true,grid:{color:'rgba(0,0,0,0.05)'}},
      yLat:{position:'right',title:{display:true,text:'ms',font:{size:10}},beginAtZero:true,grid:{display:false},min:0,max:$latYMax}
    }
  }
});
</script>
</body>
</html>"""
    }

    // ── SeekBar helper ────────────────────────────────────────────────────────

    private fun simpleListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
            if (fromUser) onChanged(p)
        }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }
}
