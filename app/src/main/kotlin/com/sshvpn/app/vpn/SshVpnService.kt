package com.sshvpn.app.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sshvpn.app.R
import com.sshvpn.app.SshVpnApp
import com.sshvpn.app.ssh.SshTunnelManager
import com.sshvpn.app.ui.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SshVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val sshTunnel = SshTunnelManager()
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newCachedThreadPool()
    private var tcpHandler: TcpHandler? = null
    private var dnsHandler: DnsHandler? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 22)
                val username = intent.getStringExtra(EXTRA_USERNAME) ?: return START_NOT_STICKY
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: return START_NOT_STICKY
                val perApp = intent.getBooleanExtra(EXTRA_PER_APP, false)
                val allowedApps = intent.getStringExtra(EXTRA_ALLOWED_APPS) ?: ""
                startVpn(host, port, username, password, perApp, allowedApps)
            }
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(
        host: String, port: Int, username: String, password: String,
        perApp: Boolean, allowedApps: String
    ) {
        startForegroundNotification("Connecting...")

        scope.launch {
            try {
                // Step 1: Connect SSH and start SOCKS proxy
                val result = sshTunnel.connect(host, port, username, password)
                if (result.isFailure) {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    broadcastStatus(STATUS_ERROR, error)
                    stopSelf()
                    return@launch
                }

                val socksPort = result.getOrThrow()

                // Step 2: Set up VPN interface
                val builder = Builder()
                    .setSession("SSH VPN")
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(1500)
                    .setBlocking(true)

                // Per-app VPN
                if (perApp && allowedApps.isNotBlank()) {
                    allowedApps.split(",").filter { it.isNotBlank() }.forEach { pkg ->
                        try {
                            builder.addAllowedApplication(pkg.trim())
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not add app: $pkg")
                        }
                    }
                }

                // Exclude our own app to prevent loops
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {}

                val pendingIntent = PendingIntent.getActivity(
                    this@SshVpnService, 0,
                    Intent(this@SshVpnService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                builder.setConfigureIntent(pendingIntent)

                vpnInterface = builder.establish()

                if (vpnInterface == null) {
                    broadcastStatus(STATUS_ERROR, "Failed to establish VPN interface")
                    sshTunnel.disconnect()
                    stopSelf()
                    return@launch
                }

                // Step 3: Start packet processing
                running.set(true)
                updateNotification("Connected to $host")
                broadcastStatus(STATUS_CONNECTED)

                tcpHandler = TcpHandler(
                    socksPort = socksPort,
                    executor = executor,
                    onResponsePacket = { writePacket(it) },
                    protectSocket = { protect(it) }
                )

                dnsHandler = DnsHandler(
                    socksPort = socksPort,
                    executor = executor,
                    onResponsePacket = { writePacket(it) },
                    protectSocket = { protect(it) },
                    protectDatagramSocket = { protect(it) }
                )

                processPackets()

            } catch (e: Exception) {
                Log.e(TAG, "VPN error", e)
                broadcastStatus(STATUS_ERROR, e.message ?: "Unknown error")
                stopVpn()
            }
        }
    }

    private fun processPackets() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val buffer = ByteBuffer.allocate(32767)

        try {
            while (running.get()) {
                buffer.clear()
                val length = input.read(buffer.array())
                if (length <= 0) continue

                buffer.limit(length)

                val version = (buffer.get(0).toInt() shr 4) and 0x0F
                if (version != 4) continue // Only handle IPv4

                val packet = Packet(buffer)

                when (packet.protocol) {
                    Packet.TCP -> tcpHandler?.handlePacket(packet)
                    Packet.UDP -> dnsHandler?.handlePacket(packet)
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "Packet processing error", e)
            }
        }
    }

    @Synchronized
    private fun writePacket(data: ByteArray) {
        try {
            val fd = vpnInterface?.fileDescriptor ?: return
            val output = FileOutputStream(fd)
            output.write(data)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Write packet error", e)
        }
    }

    private fun stopVpn() {
        running.set(false)
        tcpHandler?.shutdown()
        tcpHandler = null
        dnsHandler = null
        sshTunnel.disconnect()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        broadcastStatus(STATUS_DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification(text: String) {
        val notification = NotificationCompat.Builder(this, SshVpnApp.CHANNEL_ID)
            .setContentTitle("SSH VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, SshVpnApp.CHANNEL_ID)
            .setContentTitle("SSH VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setOngoing(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus(status: String, message: String = "") {
        val intent = Intent(ACTION_STATUS_BROADCAST).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    companion object {
        private const val TAG = "SshVpnService"
        const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.sshvpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.sshvpn.app.DISCONNECT"
        const val ACTION_STATUS_BROADCAST = "com.sshvpn.app.STATUS"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PER_APP = "per_app"
        const val EXTRA_ALLOWED_APPS = "allowed_apps"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"

        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_ERROR = "error"
    }
}
