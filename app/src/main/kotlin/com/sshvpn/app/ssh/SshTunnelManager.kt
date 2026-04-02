package com.sshvpn.app.ssh

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SshTunnelManager {

    private var session: Session? = null
    private var socksServer: SocksServer? = null
    var localSocksPort: Int = 0
        private set

    val isConnected: Boolean
        get() = session?.isConnected == true

    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            disconnect()

            val jsch = JSch()
            val newSession = jsch.getSession(username, host, port)
            newSession.setPassword(password)

            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("compression.s2c", "zlib@openssh.com,none")
                put("compression.c2s", "zlib@openssh.com,none")
            }
            newSession.setConfig(config)
            newSession.timeout = 15_000

            newSession.connect(15_000)

            // Start local SOCKS5 server that tunnels via SSH direct-tcpip channels
            val server = SocksServer(newSession)
            server.start()

            session = newSession
            socksServer = server
            localSocksPort = server.port

            Result.success(server.port)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    fun disconnect() {
        socksServer?.stop()
        socksServer = null
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        session = null
        localSocksPort = 0
    }

    /**
     * Local SOCKS5 proxy server that forwards connections through SSH direct-tcpip channels.
     * This implements the same functionality as `ssh -D`.
     */
    private class SocksServer(private val session: Session) {

        private var serverSocket: ServerSocket? = null
        private val running = AtomicBoolean(false)
        private val executor: ExecutorService = Executors.newCachedThreadPool()
        var port: Int = 0
            private set

        fun start() {
            val ss = ServerSocket(0)
            serverSocket = ss
            port = ss.localPort
            running.set(true)

            executor.submit {
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        executor.submit { handleClient(client) }
                    } catch (e: IOException) {
                        if (running.get()) {
                            Log.e(TAG, "Accept error", e)
                        }
                    }
                }
            }
        }

        fun stop() {
            running.set(false)
            try { serverSocket?.close() } catch (_: Exception) {}
            executor.shutdownNow()
        }

        private fun handleClient(client: Socket) {
            try {
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // SOCKS5 greeting
                val version = input.read()
                if (version != 0x05) {
                    client.close()
                    return
                }

                val nMethods = input.read()
                val methods = ByteArray(nMethods)
                readFully(input, methods)

                // Reply: no auth required
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // SOCKS5 connection request
                val ver = input.read() // version
                val cmd = input.read() // 1 = connect
                input.read() // reserved

                if (cmd != 0x01) {
                    // Only CONNECT is supported
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    client.close()
                    return
                }

                val addrType = input.read()
                val targetHost: String
                val targetPort: Int

                when (addrType) {
                    0x01 -> { // IPv4
                        val addr = ByteArray(4)
                        readFully(input, addr)
                        targetHost = "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
                    }
                    0x03 -> { // Domain name
                        val len = input.read()
                        val domain = ByteArray(len)
                        readFully(input, domain)
                        targetHost = String(domain)
                    }
                    0x04 -> { // IPv6
                        val addr = ByteArray(16)
                        readFully(input, addr)
                        targetHost = java.net.InetAddress.getByAddress(addr).hostAddress ?: "::1"
                    }
                    else -> {
                        client.close()
                        return
                    }
                }

                val portHigh = input.read()
                val portLow = input.read()
                targetPort = (portHigh shl 8) or portLow

                // Open SSH direct-tcpip channel to target
                val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel.setHost(targetHost)
                channel.setPort(targetPort)
                channel.connect(10_000)

                // Send success response
                output.write(byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    0, 0, 0, 0, // bind addr
                    0, 0         // bind port
                ))
                output.flush()

                // Bidirectional relay
                val channelInput = channel.inputStream
                val channelOutput = channel.outputStream

                val t1 = Thread {
                    relay(input, channelOutput)
                    try { channel.disconnect() } catch (_: Exception) {}
                }
                val t2 = Thread {
                    relay(channelInput, output)
                    try { client.close() } catch (_: Exception) {}
                }

                t1.start()
                t2.start()
                t1.join()
                t2.join()

            } catch (e: Exception) {
                Log.d(TAG, "SOCKS client error: ${e.message}")
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }

        private fun relay(input: InputStream, output: OutputStream) {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
            }
        }

        private fun readFully(input: InputStream, buf: ByteArray) {
            var offset = 0
            while (offset < buf.size) {
                val read = input.read(buf, offset, buf.size - offset)
                if (read == -1) throw IOException("Unexpected EOF")
                offset += read
            }
        }

        companion object {
            private const val TAG = "SocksServer"
        }
    }
}
