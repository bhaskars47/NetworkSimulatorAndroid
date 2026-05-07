package com.networksimulator.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.networksimulator.MainActivity
import com.networksimulator.R
import com.networksimulator.model.NetworkProfile
import com.networksimulator.model.ProfileType
import com.networksimulator.model.SimulationConfig
import com.networksimulator.stats.StatSnapshot
import com.networksimulator.stats.StatsCollector
import com.networksimulator.vpn.packet.IpPacket
import com.networksimulator.vpn.packet.TcpPacket
import com.networksimulator.vpn.packet.UdpPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * Core VPN service that intercepts, simulates, and forwards network traffic.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Data-flow overview                                                      │
 * │                                                                          │
 * │  Target App                                                              │
 * │      │ raw IP packets                                                    │
 * │      ▼                                                                   │
 * │  TUN fd (VpnService)  ──read──▶  readLoop coroutine                     │
 * │                                       │                                  │
 * │                                       ▼                                  │
 * │                              PacketProcessor                             │
 * │                            (drop? delay? throttle?)                      │
 * │                                       │                                  │
 * │                           ┌──────────┴──────────┐                       │
 * │                           ▼                     ▼                        │
 * │                      UDP relay            TcpConnectionTracker           │
 * │                   (DatagramChannel)        (SocketChannel)               │
 * │                           │                     │                        │
 * │                           ▼                     ▼                        │
 * │                       Real Internet         Real Internet                │
 * │                           │                     │                        │
 * │                           └──────────┬──────────┘                       │
 * │                                      ▼                                   │
 * │                              TUN fd ──write──▶  Target App              │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
class NetworkSimulatorVpnService : VpnService() {

    companion object {
        private const val TAG = "NetworkSimulatorVpn"

        const val ACTION_START  = "com.networksimulator.START_VPN"
        const val ACTION_STOP   = "com.networksimulator.STOP_VPN"
        const val EXTRA_CONFIG  = "extra_simulation_config"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "ns_vpn_channel"

        private const val TUN_ADDRESS = "10.66.0.1"
        private const val TUN_PREFIX  = 32
        private const val TUN_MTU     = 1500
        private const val DNS_SERVER  = "8.8.8.8"

        /** Live status flag — read by the UI without a service binding. */
        @Volatile var isRunning: Boolean = false
            private set

        /**
         * Singleton stats collector — same instance for the app's lifetime.
         * The VPN service calls reset() at start and flush() every second.
         * The ViewModel subscribes once and never loses the reference.
         */
        val stats: StatsCollector = StatsCollector()

        /**
         * Current phase label for the Train profile (e.g. "Good signal").
         * Emits "—" when no Train simulation is running.
         * Observed by MainViewModel → MainActivity to update the phase banner.
         */
        private val _currentPhaseLabel = MutableStateFlow("—")
        val currentPhaseLabel: StateFlow<String> = _currentPhaseLabel
    }

    // ── Instance state ────────────────────────────────────────────────────────

    private var vpnInterface:   ParcelFileDescriptor? = null
    private var serviceScope:   CoroutineScope?       = null
    private var tcpTracker:     TcpConnectionTracker? = null
    private var processor:      PacketProcessor       = PacketProcessor(NetworkProfile.slow3G())
    private var activeConfig:   SimulationConfig      = SimulationConfig()
    private var phaseScheduler: PhaseScheduler?       = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stopVpn()
                START_NOT_STICKY
            }
            ACTION_START -> {
                val cfg = extractConfig(intent)
                Log.i(TAG, "Start — profile: ${cfg.profile.name}, target: '${cfg.targetPackageName}'")
                activeConfig = cfg
                processor    = PacketProcessor(cfg.profile)
                stats.reset()

                // ① startForeground() first — Android requires it within 5 s.
                //    CoroutineExceptionHandler prevents EBADF / IO errors on worker
                //    threads from propagating to the global uncaught-exception handler
                //    and crashing the entire process.
                val exHandler = CoroutineExceptionHandler { _, e ->
                    Log.e(TAG, "Worker exception (suppressed): ${e.message}", e)
                }
                serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exHandler)
                showNotification()

                // ② establish() on the main thread (required on Android 14 / Nothing Phone).
                startVpn()

                // START_NOT_STICKY — do NOT restart after process death.
                // START_STICKY would replay the last intent with a null config, causing
                // a second ForegroundServiceDidNotStartInTimeException crash.
                START_NOT_STICKY
            }
            else -> {
                // Null intent = unexpected START_STICKY restart.  Just stop cleanly.
                Log.w(TAG, "onStartCommand null intent — stopping")
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by system")
        stopVpn()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ── VPN start / stop ──────────────────────────────────────────────────────

    /** Called on the main thread from onStartCommand, after startForeground(). */
    private fun startVpn() {
        Log.i(TAG, "startVpn() — building tunnel")
        try {
            val builder = Builder()
                .setSession("Network Simulator")
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addDnsServer(DNS_SERVER)
                .addRoute("0.0.0.0", 0)
                .setMtu(TUN_MTU)
                .setBlocking(true)

            if (activeConfig.targetPackageName.isNotBlank()) {
                try {
                    // Allowlist mode: ONLY this app's traffic goes through the VPN.
                    // Our own app is implicitly excluded (not in the allowlist).
                    // Cannot mix addAllowedApplication + addDisallowedApplication — Android forbids it.
                    builder.addAllowedApplication(activeConfig.targetPackageName)
                    Log.i(TAG, "Intercepting only: ${activeConfig.targetPackageName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Unknown package '${activeConfig.targetPackageName}' — intercepting all", e)
                    // Fallback: intercept all apps but exclude ourselves to avoid routing loop
                    builder.addDisallowedApplication(packageName)
                }
            } else {
                // No target specified: intercept all apps, but exclude ourselves
                builder.addDisallowedApplication(packageName)
            }

            Log.i(TAG, "Calling establish()…")
            val pfd = builder.establish()
            if (pfd == null) {
                val msg = "establish() returned null — VPN permission was not granted or was revoked. " +
                          "Open Settings → VPN and make sure Network Simulator is allowed."
                Log.e(TAG, msg)
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                stopSelf(); return
            }

            vpnInterface = pfd
            Log.i(TAG, "TUN interface established: ${pfd.fileDescriptor}")

            tcpTracker = TcpConnectionTracker()
            isRunning  = true

            launchPacketLoop()
            launchSecondTick()

            // Start phase scheduler if the Train profile is selected
            if (activeConfig.profile.type == ProfileType.TRAIN) {
                phaseScheduler = PhaseScheduler().also { scheduler ->
                    scheduler.start(serviceScope!!, processor, stats) { label ->
                        _currentPhaseLabel.value = label
                    }
                }
                Log.i(TAG, "Train PhaseScheduler launched")
            } else {
                _currentPhaseLabel.value = "—"
            }

            Log.i(TAG, "VPN fully started — packet loop + tick running")
        } catch (e: Exception) {
            val msg = "VPN start failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, msg, e)
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            isRunning = false
            stopSelf()
        }
    }

    private fun stopVpn() {
        phaseScheduler?.stop()
        phaseScheduler = null
        _currentPhaseLabel.value = "—"

        serviceScope?.cancel()
        serviceScope = null

        tcpTracker?.close()
        tcpTracker = null

        vpnInterface?.close()
        vpnInterface = null

        isRunning = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    // ── Coroutine loops ───────────────────────────────────────────────────────

    private fun launchPacketLoop() {
        val fd  = vpnInterface?.fileDescriptor ?: return
        val ris = FileInputStream(fd)
        val wos = FileOutputStream(fd)

        serviceScope?.launch {
            val buf = ByteArray(TUN_MTU + 28)
            while (isActive) {
                val len = try { ris.read(buf) } catch (e: Exception) { break }
                if (len <= 0) continue
                val packet = buf.copyOf(len)
                launch { processPacket(packet, wos) }
            }
        }
    }

    /** Fires once per second: flushes stats, updates notification, evicts idle TCP connections. */
    private fun launchSecondTick() {
        serviceScope?.launch {
            while (isActive) {
                delay(1_000L)
                val snap = stats.flush()
                updateNotification(snap)
                tcpTracker?.evictIdleConnections()
            }
        }
    }

    // ── Packet processing ─────────────────────────────────────────────────────

    private suspend fun processPacket(raw: ByteArray, out: FileOutputStream) {
        val ip = try { IpPacket(raw) } catch (e: Exception) {
            Log.v(TAG, "Non-IPv4 — skipping"); return
        }

        // Gate 1: packet loss
        if (processor.shouldDrop()) {
            stats.recordDropped()
            return
        }

        // Gate 2: latency + jitter
        val latencyApplied = applyAndMeasureLatency()

        // Gate 3: bandwidth throttle
        processor.applyBandwidthThrottle(raw.size)

        // Record throughput
        stats.recordForwarded(raw.size)
        stats.recordLatency(latencyApplied)

        when {
            ip.isUdp  -> handleUdp(ip, out)
            ip.isTcp  -> handleTcp(ip, out)
            ip.isIcmp -> Log.v(TAG, "ICMP — not forwarded")
            else      -> Log.v(TAG, "Protocol ${ip.protocol} — skipping")
        }
    }

    /** Applies latency and returns the actual ms waited (for stats). */
    private suspend fun applyAndMeasureLatency(): Long {
        val before = System.currentTimeMillis()
        processor.applyLatency()
        return System.currentTimeMillis() - before
    }

    // ── UDP forwarding ────────────────────────────────────────────────────────

    private fun handleUdp(ip: IpPacket, out: FileOutputStream) {
        val udp = try { UdpPacket(ip) } catch (e: Exception) {
            Log.w(TAG, "Malformed UDP", e); return
        }
        if (udp.payload.isEmpty()) return

        try {
            val ch = DatagramChannel.open()
            if (!protect(ch.socket())) {
                Log.w(TAG, "UDP protect() failed — skipping packet"); ch.close(); return
            }
            ch.connect(InetSocketAddress(ip.destinationAddress, udp.destinationPort))
            ch.write(ByteBuffer.wrap(udp.payload))

            ch.configureBlocking(false)
            val deadline = System.currentTimeMillis() + 3_000L
            val recvBuf  = ByteBuffer.allocate(65_535)

            while (System.currentTimeMillis() < deadline) {
                recvBuf.clear()
                val read = ch.read(recvBuf)
                if (read > 0) {
                    recvBuf.flip()
                    val resp = ByteArray(recvBuf.limit()); recvBuf.get(resp)
                    val pkt  = buildUdpPacket(
                        srcIp   = ip.destinationAddress.address,
                        dstIp   = ip.sourceAddress.address,
                        srcPort = udp.destinationPort,
                        dstPort = udp.sourcePort,
                        payload = resp
                    )
                    synchronized(out) {
                        try { out.write(pkt) }
                        catch (e: java.io.IOException) {
                            Log.w(TAG, "TUN UDP write failed (fd closed): ${e.message}")
                        }
                    }
                    break
                }
                Thread.sleep(5)
            }
            ch.close()
        } catch (e: Exception) {
            Log.e(TAG, "UDP error", e)
        }
    }

    // ── TCP forwarding ────────────────────────────────────────────────────────

    private suspend fun handleTcp(ip: IpPacket, out: FileOutputStream) {
        val tcp     = try { TcpPacket(ip) } catch (e: Exception) {
            Log.w(TAG, "Malformed TCP", e); return
        }
        val tracker = tcpTracker ?: return
        // Tracker writes response packets directly to `out` with proper TCP headers
        tracker.handleSegment(tcp, out) { ch -> protect(ch.socket()) }
    }

    // ── Packet builder ────────────────────────────────────────────────────────

    private fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen  = 20 + udpLen
        val pkt    = ByteBuffer.allocate(ipLen)

        pkt.put(0x45.toByte()); pkt.put(0)
        pkt.putShort(ipLen.toShort()); pkt.putShort(0)
        pkt.putShort(0x4000.toShort()); pkt.put(64); pkt.put(17); pkt.putShort(0)
        pkt.put(srcIp); pkt.put(dstIp)

        val arr = pkt.array()
        val cs  = IpPacket.checksum(arr, 0, 20)
        arr[10] = (cs shr 8).toByte(); arr[11] = cs.toByte()

        pkt.putShort(srcPort.toShort()); pkt.putShort(dstPort.toShort())
        pkt.putShort(udpLen.toShort()); pkt.putShort(0)
        pkt.put(payload)
        return arr
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private val stopIntent by lazy {
        PendingIntent.getService(
            this, 0,
            Intent(this, NetworkSimulatorVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val openIntent by lazy {
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Initial notification — called on the main thread from onStartCommand. */
    private fun showNotification() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW)
        )
        val target  = if (activeConfig.targetPackageName.isNotBlank())
            activeConfig.targetPackageName else "All apps"
        val profile = activeConfig.profile

        val configLine = buildString {
            append("Latency: ${profile.latencyMs} ms ± ${profile.jitterMs} ms")
            append("   Loss: ${"%.1f".format(profile.packetLossPercent)}%")
            if (profile.bandwidthKbps > 0) append("   Cap: ${profile.bandwidthKbps} kbps")
        }

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Network Simulator  ·  ${profile.name}")
                .setContentText("Starting simulation…")
                .setSubText("Target: $target")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("Starting simulation…\n$configLine\nTarget: $target")
                        .setSummaryText(profile.name)
                )
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentIntent(openIntent)
                .addAction(R.drawable.ic_stop, "Stop", stopIntent)
                .setOngoing(true)
                .setSilent(true)
                .build()
        )
    }

    /**
     * Updates the persistent notification with live stats every second.
     * Called from [launchSecondTick] — never touches foreground state, just
     * refreshes the existing notification via [NotificationManager.notify].
     *
     * Collapsed view:
     *   Title:   Network Simulator  ·  High Latency
     *   Content: ↑ 12 kbps   ⏱ 1043 ms   ✕ 0.0%   [8 pkts]
     *   SubText: Target: com.jio.jioaiphotos
     *
     * Expanded (BigTextStyle):
     *   Line 1: same as content
     *   Line 2: X packets forwarded  ·  Y dropped
     *   Line 3: Latency cfg: 1000 ms ± 200 ms   Loss cfg: 0.5%
     */
    private fun updateNotification(snap: StatSnapshot) {
        val target  = if (activeConfig.targetPackageName.isNotBlank())
            activeConfig.targetPackageName else "All apps"
        val profile = activeConfig.profile

        // ── Collapsed one-liner ───────────────────────────────────────────────
        val collapsedText = if (snap.packetsTotal == 0) {
            "Monitoring — no traffic this second"
        } else {
            val loss = if (snap.lossPercent == 0f) "No loss"
                       else "Loss: ${"%.1f".format(snap.lossPercent)}%"
            "↑ ${snap.throughputKbps} kbps   ⏱ ${snap.avgLatencyMs} ms   $loss   [${snap.packetsTotal} pkts]"
        }

        // ── Expanded detail (visible when notification is pulled down) ────────
        val droppedNote = if (snap.packetsDropped == 0) "None dropped"
                          else "${snap.packetsDropped} dropped  ⚠"
        val expandedText = buildString {
            appendLine(collapsedText)
            appendLine("Packets: ${snap.packetsTotal} forwarded  ·  $droppedNote")
            append("Cfg — Latency: ${profile.latencyMs} ms ± ${profile.jitterMs} ms")
            append("   Loss: ${"%.1f".format(profile.packetLossPercent)}%")
            if (profile.bandwidthKbps > 0) append("   Cap: ${profile.bandwidthKbps} kbps")
        }

        // For Train profile, append current phase to the title
        val notifTitle = if (activeConfig.profile.type == ProfileType.TRAIN) {
            val phase = phaseScheduler?.currentPhaseLabel ?: "—"
            "Network Simulator  ·  Train  [$phase]"
        } else {
            "Network Simulator  ·  ${profile.name}"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notifTitle)
            .setContentText(collapsedText)
            .setSubText("Target: $target")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(expandedText)
                    .setSummaryText("Target: $target")
            )
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        // Use startForeground() to update — more reliable than notify() across
        // OEM skins (Samsung, Xiaomi, etc.) which throttle notify() on foreground services.
        startForeground(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun extractConfig(intent: Intent): SimulationConfig =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getSerializableExtra(EXTRA_CONFIG, SimulationConfig::class.java) ?: SimulationConfig()
        else
            (intent.getSerializableExtra(EXTRA_CONFIG) as? SimulationConfig) ?: SimulationConfig()
}
