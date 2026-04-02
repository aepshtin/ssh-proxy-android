package com.sshvpn.app.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

class SshTunnelManager {

    private var session: Session? = null
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

            // Dynamic port forwarding creates a local SOCKS5 proxy
            val actualPort = findFreePort()
            newSession.setPortForwardingD(actualPort)

            session = newSession
            localSocksPort = actualPort

            Result.success(actualPort)
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    fun disconnect() {
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        session = null
        localSocksPort = 0
    }

    private fun findFreePort(): Int {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }
}
