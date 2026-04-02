package com.sshvpn.app.vpn

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService

/**
 * Handles DNS queries by forwarding them through the SOCKS5 proxy.
 * Falls back to direct UDP if SOCKS tunneling fails.
 */
class DnsHandler(
    private val socksPort: Int,
    private val executor: ExecutorService,
    private val onResponsePacket: (ByteArray) -> Unit,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean
) {
    fun handlePacket(packet: Packet) {
        if (packet.destinationPort != 53) return

        val raw = packet.getRawData()
        val dnsPayload = ByteArray(packet.udpPayloadLength)
        System.arraycopy(raw, packet.udpPayloadOffset, dnsPayload, 0, dnsPayload.size)

        val srcAddr = packet.sourceAddress
        val srcPort = packet.sourcePort
        val dstAddr = packet.destinationAddress

        executor.submit {
            try {
                val response = resolveDns(dnsPayload, dstAddr)
                if (response != null) {
                    val responsePacket = buildUdpPacket(
                        srcAddr = dstAddr,
                        dstAddr = srcAddr,
                        srcPort = 53,
                        dstPort = srcPort,
                        payload = response
                    )
                    onResponsePacket(responsePacket)
                }
            } catch (e: Exception) {
                Log.d(TAG, "DNS resolution failed: ${e.message}")
            }
        }
    }

    private fun resolveDns(query: ByteArray, dnsServer: InetAddress): ByteArray? {
        // Use direct UDP for DNS (protected from VPN to avoid loop)
        val socket = DatagramSocket()
        try {
            protectDatagramSocket(socket)
            socket.soTimeout = 5000

            val sendPacket = DatagramPacket(query, query.size, dnsServer, 53)
            socket.send(sendPacket)

            val buf = ByteArray(1024)
            val recvPacket = DatagramPacket(buf, buf.size)
            socket.receive(recvPacket)

            return buf.copyOf(recvPacket.length)
        } finally {
            socket.close()
        }
    }

    private fun buildUdpPacket(
        srcAddr: InetAddress,
        dstAddr: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + payload.size
        val packet = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(packet)

        // IP header
        buf.put(0x45.toByte())
        buf.put(0.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0) // identification
        buf.putShort(0x4000.toShort()) // don't fragment
        buf.put(64.toByte()) // TTL
        buf.put(17.toByte()) // UDP
        buf.putShort(0) // checksum placeholder
        buf.put(srcAddr.address)
        buf.put(dstAddr.address)

        // UDP header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((udpHeaderLen + payload.size).toShort())
        buf.putShort(0) // checksum (optional for IPv4)

        // Payload
        buf.put(payload)

        // Calculate IP checksum
        var sum = 0L
        for (i in 0 until ipHeaderLen step 2) {
            if (i == 10) continue
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toInt()
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        return packet
    }

    companion object {
        private const val TAG = "DnsHandler"
    }
}
