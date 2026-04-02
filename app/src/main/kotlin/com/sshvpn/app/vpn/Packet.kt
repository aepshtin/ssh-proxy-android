package com.sshvpn.app.vpn

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Minimal IP/TCP/UDP packet parser for VPN packet processing.
 */
class Packet(private val buffer: ByteBuffer) {

    val version: Int get() = (buffer.get(0).toInt() shr 4) and 0x0F
    val headerLength: Int get() = (buffer.get(0).toInt() and 0x0F) * 4
    val totalLength: Int get() = buffer.getShort(2).toInt() and 0xFFFF
    val protocol: Int get() = buffer.get(9).toInt() and 0xFF

    val sourceAddress: InetAddress
        get() {
            val addr = ByteArray(4)
            buffer.position(12)
            buffer.get(addr)
            return InetAddress.getByAddress(addr)
        }

    val destinationAddress: InetAddress
        get() {
            val addr = ByteArray(4)
            buffer.position(16)
            buffer.get(addr)
            return InetAddress.getByAddress(addr)
        }

    val sourcePort: Int
        get() = buffer.getShort(headerLength).toInt() and 0xFFFF

    val destinationPort: Int
        get() = buffer.getShort(headerLength + 2).toInt() and 0xFFFF

    // TCP flags
    val isTcpSyn: Boolean
        get() = protocol == TCP && (tcpFlags and TCP_SYN) != 0 && (tcpFlags and TCP_ACK) == 0

    val isTcpAck: Boolean
        get() = protocol == TCP && (tcpFlags and TCP_ACK) != 0

    val isTcpFin: Boolean
        get() = protocol == TCP && (tcpFlags and TCP_FIN) != 0

    val isTcpRst: Boolean
        get() = protocol == TCP && (tcpFlags and TCP_RST) != 0

    private val tcpFlags: Int
        get() = buffer.get(headerLength + 13).toInt() and 0xFF

    val tcpSequenceNumber: Long
        get() = buffer.getInt(headerLength + 4).toLong() and 0xFFFFFFFFL

    val tcpAckNumber: Long
        get() = buffer.getInt(headerLength + 8).toLong() and 0xFFFFFFFFL

    val tcpHeaderLength: Int
        get() = ((buffer.get(headerLength + 12).toInt() shr 4) and 0x0F) * 4

    val tcpPayloadOffset: Int
        get() = headerLength + tcpHeaderLength

    val tcpPayloadLength: Int
        get() = totalLength - tcpPayloadOffset

    val udpPayloadOffset: Int
        get() = headerLength + 8

    val udpPayloadLength: Int
        get() = totalLength - udpPayloadOffset

    fun getRawData(): ByteArray {
        val data = ByteArray(totalLength)
        buffer.position(0)
        buffer.get(data, 0, totalLength.coerceAtMost(buffer.capacity()))
        return data
    }

    companion object {
        const val TCP = 6
        const val UDP = 17

        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_ACK = 0x10

        fun buildTcpResponse(
            sourceAddr: InetAddress,
            destAddr: InetAddress,
            sourcePort: Int,
            destPort: Int,
            seqNum: Long,
            ackNum: Long,
            flags: Int,
            payload: ByteArray = ByteArray(0)
        ): ByteArray {
            val ipHeaderLen = 20
            val tcpHeaderLen = 20
            val totalLen = ipHeaderLen + tcpHeaderLen + payload.size
            val packet = ByteArray(totalLen)
            val buf = ByteBuffer.wrap(packet)

            // IP header
            buf.put((0x45).toByte()) // version 4, header length 5 words
            buf.put(0.toByte()) // TOS
            buf.putShort(totalLen.toShort())
            buf.putShort(0) // identification
            buf.putShort(0x4000.toShort()) // don't fragment
            buf.put(64.toByte()) // TTL
            buf.put(TCP.toByte())
            buf.putShort(0) // checksum placeholder
            buf.put(sourceAddr.address)
            buf.put(destAddr.address)

            // TCP header
            buf.putShort(sourcePort.toShort())
            buf.putShort(destPort.toShort())
            buf.putInt(seqNum.toInt())
            buf.putInt(ackNum.toInt())
            buf.put((0x50).toByte()) // data offset: 5 words
            buf.put(flags.toByte())
            buf.putShort(65535.toShort()) // window size
            buf.putShort(0) // checksum placeholder
            buf.putShort(0) // urgent pointer

            // payload
            if (payload.isNotEmpty()) {
                buf.put(payload)
            }

            // IP checksum
            calculateIpChecksum(packet, ipHeaderLen)

            // TCP checksum
            calculateTcpChecksum(packet, sourceAddr.address, destAddr.address,
                tcpHeaderLen + payload.size)

            return packet
        }

        private fun calculateIpChecksum(packet: ByteArray, headerLen: Int) {
            var sum = 0L
            for (i in 0 until headerLen step 2) {
                if (i == 10) continue // skip checksum field
                val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
                sum += word
            }
            while (sum shr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            val checksum = (sum.inv() and 0xFFFF).toInt()
            packet[10] = (checksum shr 8).toByte()
            packet[11] = (checksum and 0xFF).toByte()
        }

        private fun calculateTcpChecksum(
            packet: ByteArray,
            srcAddr: ByteArray,
            dstAddr: ByteArray,
            tcpLen: Int
        ) {
            val ipHeaderLen = 20
            var sum = 0L

            // Pseudo header
            for (i in 0 until 4 step 2) {
                sum += ((srcAddr[i].toInt() and 0xFF) shl 8) or (srcAddr[i + 1].toInt() and 0xFF)
                sum += ((dstAddr[i].toInt() and 0xFF) shl 8) or (dstAddr[i + 1].toInt() and 0xFF)
            }
            sum += TCP // protocol
            sum += tcpLen // TCP length

            // TCP header + data
            val end = ipHeaderLen + tcpLen
            var i = ipHeaderLen
            while (i < end - 1) {
                if (i == ipHeaderLen + 16) { i += 2; continue } // skip checksum
                val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
                sum += word
                i += 2
            }
            if (i < end) {
                sum += (packet[i].toInt() and 0xFF) shl 8
            }

            while (sum shr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            val checksum = (sum.inv() and 0xFFFF).toInt()
            packet[ipHeaderLen + 16] = (checksum shr 8).toByte()
            packet[ipHeaderLen + 17] = (checksum and 0xFF).toByte()
        }
    }
}
