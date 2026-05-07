package com.networksimulator.vpn

import android.util.Log
import com.networksimulator.vpn.packet.IpPacket
import com.networksimulator.vpn.packet.TcpPacket
import java.io.Closeable
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG                = "TcpTracker"
private const val IDLE_TIMEOUT       = 30_000L  // ms — evict idle connections
private const val FIRST_READ_TIMEOUT = 8_000L   // ms — Selector.select() for first server byte
private const val NEXT_READ_TIMEOUT  = 500L     // ms — Selector.select() for trailing segments

// TCP flag bit masks
private const val F_FIN = 0x001
private const val F_SYN = 0x002
private const val F_RST = 0x004
private const val F_PSH = 0x008
private const val F_ACK = 0x010

/**
 * Stateful TCP proxy — properly implements the 3-way handshake and data relay.
 *
 * Each [Conn] has its own [Mutex] so that multiple coroutines processing
 * back-to-back segments for the SAME TCP connection are serialised.
 * Without this, two coroutines can race inside drainChannel() — one sets the
 * channel to non-blocking while the other tries to write → ClosedChannelException.
 *
 * Flow for each HTTPS connection:
 *  1. App sends SYN  → we connect to real server, send SYN-ACK back to app.
 *  2. App sends ACK  → handshake complete.
 *  3. App sends data → forward to server, read response, send back in TCP segments.
 *  4. App sends FIN  → send FIN-ACK, close channel.
 */
class TcpConnectionTracker : Closeable {

    private class Conn(
        val channel:    SocketChannel,
        val clientIp:   ByteArray,   // source (app) IP
        val serverIp:   ByteArray,   // destination IP
        val clientPort: Int,
        val serverPort: Int,
        var clientSeq:  Long,        // next byte we expect FROM the app
        var serverSeq:  Long,        // next byte WE send TO the app
        var lastUsed:   Long = System.currentTimeMillis(),
        /** Serialises all reads/writes on this connection across coroutines. */
        val mutex:      Mutex = Mutex(),
        /** Set to true once the channel is closed — guards against post-close writes. */
        @Volatile var closed: Boolean = false
    )

    // key = "clientIP:clientPort"
    private val table = ConcurrentHashMap<String, Conn>()

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Suspend so callers on Dispatchers.IO can use [Mutex.withLock] without
     * blocking the thread for the entire duration of a drainChannel() call.
     */
    suspend fun handleSegment(
        tcp:     TcpPacket,
        out:     FileOutputStream,
        protect: (SocketChannel) -> Unit
    ) {
        val key = "${tcp.ip.sourceAddress}:${tcp.sourcePort}"
        try {
            when {
                tcp.isSyn && !tcp.isAck -> openConnection(tcp, key, out, protect)
                tcp.isRst               -> closeConn(key)
                tcp.isFin               -> sendFinAck(tcp, key, out)
                tcp.isAck               -> relayData(tcp, key, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error on $key: ${e.javaClass.simpleName}: ${e.message}")
            closeConn(key)
            sendRst(tcp, out)
        }
    }

    fun evictIdleConnections() {
        val now     = System.currentTimeMillis()
        val expired = table.entries.filter { now - it.value.lastUsed > IDLE_TIMEOUT }
        expired.forEach { (k, c) ->
            Log.d(TAG, "Evict idle: $k")
            c.channel.runCatching { close() }
            table.remove(k)
        }
    }

    override fun close() {
        table.values.forEach { it.channel.runCatching { close() } }
        table.clear()
    }

    // ── SYN handler ───────────────────────────────────────────────────────────

    private fun openConnection(
        tcp:     TcpPacket,
        key:     String,
        out:     FileOutputStream,
        protect: (SocketChannel) -> Unit
    ) {
        table.remove(key)?.channel?.runCatching { close() }

        val ch = SocketChannel.open()
        protect(ch)                             // exempt from VPN — prevents routing loop
        ch.socket().tcpNoDelay = true
        ch.connect(InetSocketAddress(tcp.ip.destinationAddress, tcp.destinationPort))

        val ourIsn = Random.nextLong(1L, 0xFFFFF000L)
        val conn = Conn(
            channel    = ch,
            clientIp   = tcp.ip.sourceAddress.address.clone(),
            serverIp   = tcp.ip.destinationAddress.address.clone(),
            clientPort = tcp.sourcePort,
            serverPort = tcp.destinationPort,
            clientSeq  = (tcp.sequenceNumber + 1L) and 0xFFFF_FFFFL,  // SYN = 1 seq
            serverSeq  = (ourIsn + 1L)             and 0xFFFF_FFFFL   // SYN = 1 seq
        )
        table[key] = conn
        Log.d(TAG, "SYN opened: $key → ${tcp.ip.destinationAddress}:${tcp.destinationPort}")

        // Send SYN-ACK — completes step 2 of 3-way handshake
        writePacket(conn.serverIp, conn.clientIp, conn.serverPort, conn.clientPort,
            seq = ourIsn, ack = conn.clientSeq, flags = F_SYN or F_ACK,
            payload = ByteArray(0), out = out)
    }

    // ── ACK / data relay ──────────────────────────────────────────────────────

    /**
     * Serialised via [Conn.mutex] — only ONE coroutine may be inside this
     * function for a given connection at a time.  This prevents the race where
     * two back-to-back TCP segments both call drainChannel() concurrently,
     * causing one coroutine to see the channel in non-blocking mode while the
     * other tries to write → ClosedChannelException (null message).
     */
    private suspend fun relayData(tcp: TcpPacket, key: String, out: FileOutputStream) {
        val conn = table[key] ?: return

        conn.mutex.withLock {
            if (conn.closed) return@withLock   // connection already torn down

            conn.lastUsed = System.currentTimeMillis()

            if (tcp.payload.isEmpty()) return@withLock  // pure ACK — keepalive or handshake step 3

            // Update expected client sequence number
            conn.clientSeq = (tcp.sequenceNumber + tcp.payload.size.toLong()) and 0xFFFF_FFFFL

            // Immediately ACK the data so the app doesn't stall waiting for a combined ACK+data
            writePacket(conn.serverIp, conn.clientIp, conn.serverPort, conn.clientPort,
                seq = conn.serverSeq, ack = conn.clientSeq, flags = F_ACK,
                payload = ByteArray(0), out = out)

            // Forward to real server
            conn.channel.write(ByteBuffer.wrap(tcp.payload))
            Log.v(TAG, "→ server ${tcp.payload.size}B  key=$key")

            // Collect all response bytes (blocks until server is done or timeout)
            val response = drainChannel(conn.channel)
            if (response.isEmpty()) return@withLock
            Log.v(TAG, "← server ${response.size}B  key=$key")

            // Send back in MSS-sized TCP segments
            val mss = 1460
            var offset = 0
            while (offset < response.size) {
                val end   = minOf(offset + mss, response.size)
                val chunk = response.copyOfRange(offset, end)
                val flags = if (end >= response.size) F_PSH or F_ACK else F_ACK

                writePacket(conn.serverIp, conn.clientIp, conn.serverPort, conn.clientPort,
                    seq = conn.serverSeq, ack = conn.clientSeq, flags = flags,
                    payload = chunk, out = out)

                conn.serverSeq = (conn.serverSeq + chunk.size.toLong()) and 0xFFFF_FFFFL
                offset = end
            }
        }
    }

    // ── FIN handler ───────────────────────────────────────────────────────────

    private suspend fun sendFinAck(tcp: TcpPacket, key: String, out: FileOutputStream) {
        val conn = table.remove(key) ?: return

        conn.mutex.withLock {
            conn.closed = true   // prevent any in-flight relayData from writing after close
            val ack = (tcp.sequenceNumber + 1L) and 0xFFFF_FFFFL  // FIN = 1 seq

            writePacket(conn.serverIp, conn.clientIp, conn.serverPort, conn.clientPort,
                seq = conn.serverSeq, ack = ack, flags = F_FIN or F_ACK,
                payload = ByteArray(0), out = out)

            conn.channel.runCatching { close() }
            Log.d(TAG, "FIN-ACK: $key")
        }
    }

    private fun sendRst(tcp: TcpPacket, out: FileOutputStream) {
        writePacket(
            srcIp = tcp.ip.destinationAddress.address, dstIp = tcp.ip.sourceAddress.address,
            srcPort = tcp.destinationPort, dstPort = tcp.sourcePort,
            seq = tcp.ackNumber, ack = 0L, flags = F_RST,
            payload = ByteArray(0), out = out)
    }

    private fun closeConn(key: String) {
        val conn = table.remove(key) ?: return
        conn.closed = true
        conn.channel.runCatching { close() }
    }

    // ── Channel drain ─────────────────────────────────────────────────────────

    /**
     * Reads all data the server sends in response to one client request.
     *
     * Called ONLY from within [relayData]'s [Conn.mutex] lock, so no other
     * coroutine can be writing to or reading from [ch] concurrently.
     *
     * Uses NIO Selector for proper read timeouts — SocketChannel.read() in
     * blocking mode does NOT respect soTimeout on Android/Linux.
     *
     * Strategy:
     *  - [FIRST_READ_TIMEOUT] (8 s) for the first server byte.
     *  - [NEXT_READ_TIMEOUT]  (500 ms) for every subsequent segment — collects
     *    multi-segment TLS responses without blocking too long.
     *  - Exits cleanly when the selector times out (= server is done sending).
     */
    private fun drainChannel(ch: SocketChannel): ByteArray {
        val chunks   = mutableListOf<ByteArray>()
        val buf      = ByteBuffer.allocate(65_535)
        val selector = Selector.open()

        ch.configureBlocking(false)
        val key = ch.register(selector, SelectionKey.OP_READ)

        try {
            var timeoutMs = FIRST_READ_TIMEOUT

            while (true) {
                if (selector.select(timeoutMs) == 0) break   // timed out — done

                selector.selectedKeys().clear()
                buf.clear()
                val n = try { ch.read(buf) } catch (e: Exception) {
                    Log.w(TAG, "drainChannel read: ${e.message}"); break
                }
                if (n <= 0) break

                buf.flip()
                chunks.add(buf.array().copyOf(buf.limit()))
                timeoutMs = NEXT_READ_TIMEOUT   // tighter window for trailing segments
            }
        } catch (e: Exception) {
            Log.w(TAG, "drainChannel: ${e.message}")
        } finally {
            key.cancel()
            selector.close()
            ch.configureBlocking(true)    // restore for subsequent writes
        }

        val total  = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var pos    = 0
        chunks.forEach { c -> c.copyInto(result, pos); pos += c.size }
        return result
    }

    // ── TCP/IP packet builder ─────────────────────────────────────────────────

    private fun writePacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int,     dstPort: Int,
        seq: Long,        ack: Long,
        flags: Int,
        payload: ByteArray,
        out: FileOutputStream
    ) {
        val tcpLen = 20 + payload.size
        val ipLen  = 20 + tcpLen
        val buf    = ByteBuffer.allocate(ipLen)

        // IPv4 header
        buf.put(0x45.toByte()); buf.put(0)
        buf.putShort(ipLen.toShort())
        buf.putShort((seq and 0xFFFFL).toShort())   // ID — reuse low seq bits
        buf.putShort(0x4000.toShort())               // DF flag
        buf.put(64); buf.put(6); buf.putShort(0)    // TTL, proto=TCP, checksum=0
        buf.put(srcIp); buf.put(dstIp)

        val arr  = buf.array()
        val ipCs = IpPacket.checksum(arr, 0, 20)
        arr[10] = (ipCs shr 8).toByte(); arr[11] = ipCs.toByte()

        // TCP header
        buf.putShort(srcPort.toShort()); buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt()); buf.putInt(ack.toInt())
        buf.putShort((0x5000 or flags).toShort())   // offset=5 words (20B), flags
        buf.putShort(65535.toShort())               // window
        buf.putShort(0); buf.putShort(0)            // checksum=0, urgent=0

        if (payload.isNotEmpty()) buf.put(payload)

        val tcpCs = tcpChecksum(srcIp, dstIp, arr, 20, tcpLen)
        arr[36] = (tcpCs shr 8).toByte(); arr[37] = tcpCs.toByte()

        synchronized(out) {
            try { out.write(arr) }
            catch (e: java.io.IOException) {
                Log.w(TAG, "TUN write failed (fd closed): ${e.message}")
            }
        }
    }

    // TCP checksum: RFC 793 pseudo-header + TCP header + data
    private fun tcpChecksum(
        src: ByteArray, dst: ByteArray,
        pkt: ByteArray, tcpOff: Int, tcpLen: Int
    ): Int {
        var s = 0L
        s += w(src[0], src[1]); s += w(src[2], src[3])
        s += w(dst[0], dst[1]); s += w(dst[2], dst[3])
        s += 6L; s += tcpLen.toLong()
        var i = tcpOff
        while (i < tcpOff + tcpLen - 1) { s += w(pkt[i], pkt[i + 1]); i += 2 }
        if (tcpLen % 2 != 0) s += (pkt[tcpOff + tcpLen - 1].toInt() and 0xFF).toLong() shl 8
        while (s shr 16 != 0L) s = (s and 0xFFFFL) + (s shr 16)
        return (s.inv() and 0xFFFFL).toInt()
    }

    private fun w(a: Byte, b: Byte) =
        (((a.toInt() and 0xFF) shl 8) or (b.toInt() and 0xFF)).toLong()
}
