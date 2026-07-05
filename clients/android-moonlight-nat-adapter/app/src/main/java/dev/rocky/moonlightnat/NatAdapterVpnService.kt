package dev.rocky.moonlightnat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import org.amnezia.awg.hevtunnel.TProxyService
import java.io.File
import java.net.InetAddress
import kotlin.concurrent.thread

class NatAdapterVpnService : VpnService() {
    private var mappingProvider: WebhookMappingProvider? = null
    private var socksServer: DynamicSocksServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var tproxyThread: Thread? = null
    private var nativeTunNatEnabled = false
    @Volatile private var running = false
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStopAdapter()
            ACTION_START -> {
                val webhookUrl = intent.getStringExtra(EXTRA_WEBHOOK_URL).orEmpty()
                val virtualIp = intent.getStringExtra(EXTRA_VIRTUAL_IP).orEmpty()
                    .ifBlank { SettingsStore.DEFAULT_VIRTUAL_IP }
                startAdapter(webhookUrl, virtualIp)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAdapter()
        super.onDestroy()
    }

    private fun startAdapter(webhookUrl: String, virtualIp: String) {
        if (running) {
            AdapterStatus.log("restarting VPN")
            stopAdapter()
        }
        AdapterStatus.resetTraffic()
        AdapterStatus.log("starting VPN with virtual host $virtualIp")
        startForeground(NOTIFICATION_ID, buildNotification("Starting"))
        stopping = false
        nativeTunNatEnabled = false

        try {
            val vpnAddress = vpnInterfaceAddress(virtualIp)
            val provider = WebhookMappingProvider(webhookUrl, virtualIp) { mapping ->
                if (nativeTunNatEnabled) {
                    val ok = runCatching {
                        NativeTunNat.updateMapping(
                            mapping.host,
                            mapping.udp[47998] ?: 0,
                            mapping.udp[47999] ?: 0,
                            mapping.udp[48000] ?: 0,
                        )
                    }.getOrDefault(false)
                    if (!ok) {
                        AdapterStatus.error("native TUN UDP NAT mapping update failed")
                    }
                }
            }
            provider.start()
            mappingProvider = provider

            val socks = DynamicSocksServer(this, provider, virtualIp)
            val socksPort = socks.start()
            socksServer = socks

            val tun = buildVpn(vpnAddress, virtualIp)
            tunFd = tun
            val nativeFd = runCatching {
                NativeTunNat.start(tun.fd, this, vpnAddress, virtualIp)
            }.getOrDefault(-1)
            val hevtunnelFd = if (nativeFd >= 0) {
                nativeTunNatEnabled = true
                AdapterStatus.log("native TUN UDP NAT enabled")
                nativeFd
            } else {
                nativeTunNatEnabled = false
                AdapterStatus.error("native TUN UDP NAT unavailable; using SOCKS5 UDP fallback")
                tun.fd
            }
            val configFile = writeHevConfig(vpnAddress, socksPort)
            running = true
            AdapterStatus.setVpnRunning(true)
            AdapterStatus.log("TUN established: $vpnAddress/24 route $virtualIp/32")
            updateNotification("Running")

            tproxyThread = thread(name = "hevtunnel-main", isDaemon = true) {
                val result = runCatching {
                    TProxyService.TProxyStartService(configFile.absolutePath, hevtunnelFd)
                }.onFailure {
                    AdapterStatus.log("hevtunnel crashed: ${it.message}")
                }.getOrNull()
                AdapterStatus.log("hevtunnel stopped: ${result ?: "error"}")
                running = false
                AdapterStatus.setVpnRunning(false)
            }

            thread(name = "hevtunnel-stats", isDaemon = true) {
                while (running) {
                    runCatching { AdapterStatus.setTunnelStats(TProxyService.TProxyGetStats()) }
                    Thread.sleep(if (AdapterStatus.isVerboseLogging()) 1_000L else 2_500L)
                }
            }
        } catch (error: Exception) {
            AdapterStatus.log("VPN start failed: ${error.message}")
            stopAdapter()
        }
    }

    private fun requestStopAdapter() {
        if (stopping) return
        stopping = true
        AdapterStatus.log("stop requested")
        thread(name = "vpn-stop", isDaemon = true) {
            stopAdapter()
        }
    }

    private fun stopAdapter() {
        if (!running && mappingProvider == null && socksServer == null) {
            stopping = false
            stopForegroundCompat()
            stopSelf()
            return
        }
        AdapterStatus.log("stopping VPN")
        running = false
        runCatching { socksServer?.stop() }
        socksServer = null
        runCatching { mappingProvider?.stop() }
        mappingProvider = null
        if (nativeTunNatEnabled) {
            runCatching { NativeTunNat.stop() }
                .onSuccess { AdapterStatus.log("native TUN UDP NAT stopped") }
                .onFailure { AdapterStatus.log("native TUN UDP NAT stop failed: ${it.message}") }
            nativeTunNatEnabled = false
        }
        runCatching { tunFd?.close() }
            .onSuccess { AdapterStatus.log("closed VPN ParcelFileDescriptor") }
            .onFailure { AdapterStatus.log("close VPN fd failed: ${it.message}") }
        tunFd = null

        runCatching { TProxyService.TProxyStopService() }
            .onSuccess { AdapterStatus.log("hevtunnel stop requested") }
            .onFailure { AdapterStatus.log("hevtunnel stop failed: ${it.message}") }
        tproxyThread?.join(800)
        tproxyThread = null
        AdapterStatus.setVpnRunning(false)
        stopping = false
        stopForegroundCompat()
        stopSelf()
        AdapterStatus.log("VPN stopped")
    }

    private fun buildVpn(vpnAddress: String, virtualIp: String): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("Moonlight NAT Adapter")
            .setMtu(1500)
            .addAddress(vpnAddress, 24)
            .addRoute(virtualIp, 32)
            .allowBypass()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        return builder.establish() ?: throw IllegalStateException("VpnService.Builder.establish returned null")
    }

    private fun writeHevConfig(vpnAddress: String, socksPort: Int): File {
        val file = File(cacheDir, "hev-socks5-tunnel.yml")
        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: 1500
              multi-queue: false
              ipv4: $vpnAddress
            socks5:
              port: $socksPort
              address: 127.0.0.1
              udp: 'udp'
            misc:
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-level: warn
            """.trimIndent(),
        )
        return file
    }

    private fun vpnInterfaceAddress(virtualIp: String): String {
        val address = InetAddress.getByName(virtualIp).address
        require(address.size == 4) { "Virtual Sunshine IP must be IPv4" }
        val bytes = address.copyOf()
        bytes[3] = if ((address[3].toInt() and 0xff) == 1) 254.toByte() else 1
        return InetAddress.getByAddress(bytes).hostAddress ?: "10.66.66.1"
    }

    private fun buildNotification(state: String): Notification {
        ensureNotificationChannel()
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Moonlight NAT Adapter")
            .setContentText(state)
            .setSmallIcon(dev.rocky.moonlightnat.R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Moonlight NAT Adapter",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "dev.rocky.moonlightnat.START"
        const val ACTION_STOP = "dev.rocky.moonlightnat.STOP"
        const val EXTRA_WEBHOOK_URL = "webhook_url"
        const val EXTRA_VIRTUAL_IP = "virtual_ip"
        private const val CHANNEL_ID = "moonlight_nat_adapter"
        private const val NOTIFICATION_ID = 16666
    }
}
