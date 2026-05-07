package com.networksimulator.vpn.packet

import java.nio.ByteBuffer

/**
 * Parses a UDP datagram embedded in an [IpPacket].
 *
 * UDP header (RFC 768):
 * ┌──────────────────┬──────────────────┐
 * │   Source Port    │   Dest Port      │  bytes 0–3
 * ├──────────────────┼──────────────────┤
 * │     Length       │   Checksum       │  bytes 4–7
 * └──────────────────┴──────────────────┘
 * │               Payload               │
 */
class UdpPacket(val ip: IpPacket) {

    val sourcePort:      Int
    val destinationPort: Int
    val length:          Int   // UDP length field (header + payload)
    val payload:         ByteArray

    init {
        require(ip.payloadLength >= 8) { "UDP header truncated" }

        val buf = ByteBuffer.wrap(ip.rawData, ip.payloadOffset, ip.payloadLength)
        sourcePort      = buf.short.toInt() and 0xFFFF
        destinationPort = buf.short.toInt() and 0xFFFF
        length          = buf.short.toInt() and 0xFFFF
        buf.short                           // checksum — skip

        val payloadLen = (length - 8).coerceAtLeast(0)
        payload = ByteArray(payloadLen)
        if (payloadLen > 0) buf.get(payload)
    }
}
