package com.networksimulator.vpn.packet

import java.nio.ByteBuffer

/**
 * Parses a TCP segment embedded in an [IpPacket].
 *
 * TCP header (RFC 793):
 * ┌──────────────────┬──────────────────┐
 * │   Source Port    │   Dest Port      │  0–3
 * ├──────────────────────────────────────┤
 * │           Sequence Number            │  4–7
 * ├──────────────────────────────────────┤
 * │         Acknowledgement Number       │  8–11
 * ├──────┬───────────┬──────────────────┤
 * │ Offset│ Reserved │      Flags       │  12–13
 * ├──────────────────┬──────────────────┤
 * │     Window Size  │    Checksum      │  14–17
 * ├──────────────────┬──────────────────┤
 * │  Urgent Pointer  │   Options ...    │  18–
 * └──────────────────┴──────────────────┘
 */
class TcpPacket(val ip: IpPacket) {

    val sourcePort:      Int
    val destinationPort: Int
    val sequenceNumber:  Long   // unsigned int → Long
    val ackNumber:       Long
    val dataOffset:      Int    // bytes
    val flags:           Int    // low 9 bits
    val payload:         ByteArray

    // ── Flag accessors ────────────────────────────────────────────────────────
    val isFin: Boolean get() = flags and 0x001 != 0
    val isSyn: Boolean get() = flags and 0x002 != 0
    val isRst: Boolean get() = flags and 0x004 != 0
    val isPsh: Boolean get() = flags and 0x008 != 0
    val isAck: Boolean get() = flags and 0x010 != 0

    init {
        require(ip.payloadLength >= 20) { "TCP header truncated" }

        val buf = ByteBuffer.wrap(ip.rawData, ip.payloadOffset, ip.payloadLength)
        sourcePort      = buf.short.toInt()  and 0xFFFF
        destinationPort = buf.short.toInt()  and 0xFFFF
        sequenceNumber  = buf.int.toLong()   and 0xFFFF_FFFFL
        ackNumber       = buf.int.toLong()   and 0xFFFF_FFFFL

        val offsetAndFlags = buf.short.toInt() and 0xFFFF
        dataOffset      = ((offsetAndFlags shr 12) and 0xF) * 4
        flags           = offsetAndFlags and 0x1FF

        buf.short   // window
        buf.short   // checksum
        buf.short   // urgent pointer

        val payloadStart = ip.payloadOffset + dataOffset
        val payloadLen   = (ip.totalLength - ip.headerLength - dataOffset).coerceAtLeast(0)
        payload = if (payloadLen > 0) {
            ip.rawData.copyOfRange(payloadStart, payloadStart + payloadLen)
        } else {
            ByteArray(0)
        }
    }

    /** Unique 4-tuple key for connection tracking. */
    val connectionKey: String
        get() = "${ip.sourceAddress}:$sourcePort->${ip.destinationAddress}:$destinationPort"
}
