package com.sshvpn.app.vpn

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Handles TCP connections by proxying them through a local SOCKS5 proxy.
 * Each TCP flow (identified by src/dst IP+port) gets its own SOCKS5 connection.
 */
class TcpHandler(
    private val socksPort: Int,
    private val executor: ExecutorService,
    private val onResponsePacket: (ByteArray) -> Unit,
    private val protectSocket: (Socket) -> Boolean
) {
    private val connections = ConcurrentHashMap<String, TcpConnection>()

    data class TcpConnection(
        val key: String,
        val srcAddr: InetAddress,
        val dstAddr: InetAddress,
        val srcPort: Int,
        val dstPort: Int,
        var socket: Socket? = null,
        var outputStream: OutputStream? = null,
        var inputStream: InputStream? = null,
        var localSeqNum: Long = (Math.random() * Int.MAX_VALUE).toLong(),
        var remoteSeqNum: Long = 0,
        var state: State = State.CLOSED
    ) {
        enum class State { CLOSED, SYN_RECEIVED, ESTABLISHED, FIN_WAIT, CLOSING }
    }

    fun handlePacket(packet: Packet) {
        val key = "${packet.sourceAddress}:${packet.sourcePort}-${packet.destinationAddress}:${packet.destinationPort}"

        when {
            packet.isTcpSyn -> handleSyn(key, packet)
            packet.isTcpRst -> handleRst(key)
            packet.isTcpFin -> handleFin(key, packet)
            packet.isTcpAck -> handleAck(key, packet)
        }
    }

    private fun handleSyn(key: String, packet: Packet) {
        val conn = TcpConnection(
            key = key,
            srcAddr = packet.sourceAddress,
            dstAddr = packet.destinationAddress,
            srcPort = packet.sourcePort,
            dstPort = packet.destinationPort,
            remoteSeqNum = packet.tcpSequenceNumber + 1
        )
        conn.state = TcpConnection.State.SYN_RECEIVED
        connections[key] = conn

        // Connect through SOCKS proxy in background
        executor.submit {
            try {
                val socket = Socket()
                protectSocket(socket)

                // Connect to local SOCKS5 proxy
                connectViaSocks(socket, conn.dstAddr, conn.dstPort)

                conn.socket = socket
                conn.outputStream = socket.getOutputStream()
                conn.inputStream = socket.getInputStream()

                // Send SYN-ACK back
                val synAck = Packet.buildTcpResponse(
                    sourceAddr = conn.dstAddr,
                    destAddr = conn.srcAddr,
                    sourcePort = conn.dstPort,
                    destPort = conn.srcPort,
                    seqNum = conn.localSeqNum,
                    ackNum = conn.remoteSeqNum,
                    flags = Packet.TCP_SYN or Packet.TCP_ACK
                )
                conn.localSeqNum++
                onResponsePacket(synAck)

                // Start reading from remote
                startRemoteReader(conn)
            } catch (e: Exception) {
                Log.d(TAG, "SOCKS connect failed for $key: ${e.message}")
                sendRst(conn)
                connections.remove(key)
            }
        }
    }

    private fun connectViaSocks(socket: Socket, destAddr: InetAddress, destPort: Int) {
        socket.connect(InetSocketAddress("127.0.0.1", socksPort), 10_000)

        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        // SOCKS5 handshake - no auth
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()

        val authResp = ByteArray(2)
        readFully(inp, authResp)
        if (authResp[0] != 0x05.toByte() || authResp[1] != 0x00.toByte()) {
            throw Exception("SOCKS5 auth failed")
        }

        // SOCKS5 connect request
        val addrBytes = destAddr.address
        val request = ByteArray(10)
        request[0] = 0x05 // version
        request[1] = 0x01 // connect
        request[2] = 0x00 // reserved
        request[3] = 0x01 // IPv4
        System.arraycopy(addrBytes, 0, request, 4, 4)
        request[8] = (destPort shr 8).toByte()
        request[9] = (destPort and 0xFF).toByte()
        out.write(request)
        out.flush()

        // Read response
        val resp = ByteArray(10)
        readFully(inp, resp)
        if (resp[1] != 0x00.toByte()) {
            throw Exception("SOCKS5 connect failed, status: ${resp[1]}")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read == -1) throw Exception("Unexpected EOF")
            offset += read
        }
    }

    private fun handleAck(key: String, packet: Packet) {
        val conn = connections[key] ?: return

        if (conn.state == TcpConnection.State.SYN_RECEIVED) {
            conn.state = TcpConnection.State.ESTABLISHED
        }

        // Forward payload data to remote
        if (packet.tcpPayloadLength > 0 && conn.state == TcpConnection.State.ESTABLISHED) {
            val payload = ByteArray(packet.tcpPayloadLength)
            val raw = packet.getRawData()
            System.arraycopy(raw, packet.tcpPayloadOffset, payload, 0, packet.tcpPayloadLength)

            conn.remoteSeqNum = packet.tcpSequenceNumber + packet.tcpPayloadLength

            executor.submit {
                try {
                    conn.outputStream?.write(payload)
                    conn.outputStream?.flush()

                    // Send ACK
                    val ack = Packet.buildTcpResponse(
                        sourceAddr = conn.dstAddr,
                        destAddr = conn.srcAddr,
                        sourcePort = conn.dstPort,
                        destPort = conn.srcPort,
                        seqNum = conn.localSeqNum,
                        ackNum = conn.remoteSeqNum,
                        flags = Packet.TCP_ACK
                    )
                    onResponsePacket(ack)
                } catch (e: Exception) {
                    Log.d(TAG, "Write to remote failed: ${e.message}")
                    sendRst(conn)
                    closeConnection(key)
                }
            }
        }
    }

    private fun handleFin(key: String, packet: Packet) {
        val conn = connections[key] ?: return
        conn.remoteSeqNum = packet.tcpSequenceNumber + 1

        // Send FIN-ACK
        val finAck = Packet.buildTcpResponse(
            sourceAddr = conn.dstAddr,
            destAddr = conn.srcAddr,
            sourcePort = conn.dstPort,
            destPort = conn.srcPort,
            seqNum = conn.localSeqNum,
            ackNum = conn.remoteSeqNum,
            flags = Packet.TCP_FIN or Packet.TCP_ACK
        )
        conn.localSeqNum++
        onResponsePacket(finAck)

        closeConnection(key)
    }

    private fun handleRst(key: String) {
        closeConnection(key)
    }

    private fun startRemoteReader(conn: TcpConnection) {
        executor.submit {
            val buffer = ByteArray(4096)
            try {
                while (conn.state == TcpConnection.State.ESTABLISHED ||
                    conn.state == TcpConnection.State.SYN_RECEIVED
                ) {
                    val read = conn.inputStream?.read(buffer) ?: -1
                    if (read == -1) break

                    val payload = buffer.copyOf(read)
                    val dataPacket = Packet.buildTcpResponse(
                        sourceAddr = conn.dstAddr,
                        destAddr = conn.srcAddr,
                        sourcePort = conn.dstPort,
                        destPort = conn.srcPort,
                        seqNum = conn.localSeqNum,
                        ackNum = conn.remoteSeqNum,
                        flags = Packet.TCP_ACK,
                        payload = payload
                    )
                    conn.localSeqNum += read
                    onResponsePacket(dataPacket)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Remote read ended: ${e.message}")
            }

            // Send FIN
            if (conn.state == TcpConnection.State.ESTABLISHED) {
                conn.state = TcpConnection.State.FIN_WAIT
                val fin = Packet.buildTcpResponse(
                    sourceAddr = conn.dstAddr,
                    destAddr = conn.srcAddr,
                    sourcePort = conn.dstPort,
                    destPort = conn.srcPort,
                    seqNum = conn.localSeqNum,
                    ackNum = conn.remoteSeqNum,
                    flags = Packet.TCP_FIN or Packet.TCP_ACK
                )
                conn.localSeqNum++
                onResponsePacket(fin)
            }
        }
    }

    private fun sendRst(conn: TcpConnection) {
        val rst = Packet.buildTcpResponse(
            sourceAddr = conn.dstAddr,
            destAddr = conn.srcAddr,
            sourcePort = conn.dstPort,
            destPort = conn.srcPort,
            seqNum = conn.localSeqNum,
            ackNum = conn.remoteSeqNum,
            flags = Packet.TCP_RST or Packet.TCP_ACK
        )
        onResponsePacket(rst)
    }

    private fun closeConnection(key: String) {
        val conn = connections.remove(key) ?: return
        conn.state = TcpConnection.State.CLOSED
        try {
            conn.socket?.close()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        connections.keys.toList().forEach { closeConnection(it) }
    }

    companion object {
        private const val TAG = "TcpHandler"
    }
}
