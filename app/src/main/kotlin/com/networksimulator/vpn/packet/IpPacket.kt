package com.networksimulator.vpn.packet

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Minimal IPv4 header parser.
 *
 * The TUN interface delivers raw IP packets.  This class decodes enough of the
 * IPv4 header to route the packet correctly and hand its payload off to the
 * appropriate transport-layer handler (TCP or UDP).
 *
 * IPv4 header layout (RFC 791):
 * ┌─────┬─────┬───────────────────────────────┐
 * │ Ver │ IHL │  DSCP / ECN  │  Total Length  │  bytes 0–3
 * ├─────────────────────────────────────────────┤
 * │       Identification     │ Flags│Frag Offs │  bytes 4–7
 * ├─────────────────────────────────────────────┤
 * │  TTL  │ Protocol │    Header Checksum       │  bytes 8–11
 * ├─────────────────────────────────────────────┤
 * │               Source Address                │  bytes 12–15
 * ├─────────────────────────────────────────────┤
 * │            Destination Address              │  bytes 16–19
 * ├─────────────────────────────────────────────┤
 * │          Options (if IHL > 5)               │
 * └─────────────────────────────────────────────┘
 */
class IpPacket(val rawData: ByteArray) {

    val version:            Int
    val headerLength:       Int   // bytes (IHL × 4)
    val totalLength:        Int
    val protocol:           Int
    val sourceAddress:      InetAddress
    val destinationAddress: InetAddress

    init {
        require(rawData.size >= 20) { "Buffer too small for IPv4 header: ${rawData.size} bytes" }

        val buf = ByteBuffer.wrap(rawData)

        val versionIHL = buf.get().toInt() and 0xFF
        version      = (versionIHL shr 4) and 0xF
        headerLength = (versionIHL and 0xF) * 4

        buf.get()                           // DSCP / ECN — ignored
        totalLength = buf.short.toInt() and 0xFFFF

        buf.getInt()                        // ID + Flags + Fragment Offset — ignored
        buf.get()                           // TTL — ignored

        protocol = buf.get().toInt() and 0xFF
        buf.short                           // Header checksum — ignored (kernel already validated)

        val srcBytes = ByteArray(4); buf.get(srcBytes)
        sourceAddress = InetAddress.getByAddress(srcBytes)

        val dstBytes = ByteArray(4); buf.get(dstBytes)
        destinationAddress = InetAddress.getByAddress(dstBytes)
    }

    // ── Transport-layer helpers ──────────────────────────────────────────────

    /** Byte offset in [rawData] where the transport-layer payload starts. */
    val payloadOffset: Int  get() = headerLength
    val payloadLength: Int  get() = totalLength - headerLength

    val isTcp:  Boolean get() = protocol == PROTOCOL_TCP
    val isUdp:  Boolean get() = protocol == PROTOCOL_UDP
    val isIcmp: Boolean get() = protocol == PROTOCOL_ICMP

    companion object {
        const val PROTOCOL_ICMP = 1
        const val PROTOCOL_TCP  = 6
        const val PROTOCOL_UDP  = 17

        /**
         * Calculate the one's-complement Internet checksum (RFC 1071) over
         * [length] bytes of [data] starting at [offset].
         *
         * Used for building synthetic response packets.
         */
        fun checksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0
            var i = offset
            while (i < offset + length - 1) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (length % 2 != 0) {
                sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
            }
            while (sum shr 16 != 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return sum.inv() and 0xFFFF
        }
    }
}
