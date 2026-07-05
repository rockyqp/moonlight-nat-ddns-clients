package dev.rocky.moonlightnat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class RuntimeSnapshot(
    val vpnRunning: Boolean,
    val webhookStatus: String,
    val mapping: PortMapping?,
    val tcpSessions: Int,
    val udpSessions: Int,
    val txBytes: Long,
    val rxBytes: Long,
    val tunnelStats: LongArray?,
    val verboseLogging: Boolean,
    val logs: List<String>,
)

object AdapterStatus {
    private const val MAX_LOGS = 120
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val txBytes = AtomicLong(0L)
    private val rxBytes = AtomicLong(0L)

    private var vpnRunning = false
    private var webhookStatus = "idle"
    private var mapping: PortMapping? = null
    private var tcpSessions = 0
    private var udpSessions = 0
    private var tunnelStats: LongArray? = null
    private var verboseLogging = false
    private val logs = ArrayDeque<String>()

    fun setVpnRunning(value: Boolean) = synchronized(lock) {
        vpnRunning = value
    }

    fun setWebhookStatus(value: String) = synchronized(lock) {
        webhookStatus = value
    }

    fun setMapping(value: PortMapping?) = synchronized(lock) {
        mapping = value
    }

    fun setTcpSessions(value: Int) = synchronized(lock) {
        tcpSessions = value
    }

    fun setUdpSessions(value: Int) = synchronized(lock) {
        udpSessions = value
    }

    fun addTxBytes(value: Long) {
        txBytes.addAndGet(value)
    }

    fun addRxBytes(value: Long) {
        rxBytes.addAndGet(value)
    }

    fun setTunnelStats(value: LongArray?) = synchronized(lock) {
        tunnelStats = value
    }

    fun setVerboseLogging(value: Boolean) = synchronized(lock) {
        verboseLogging = value
        appendLogLocked("verbose logs ${if (value) "enabled" else "disabled"}")
    }

    fun isVerboseLogging(): Boolean = synchronized(lock) {
        verboseLogging
    }

    fun resetTraffic() = synchronized(lock) {
        tcpSessions = 0
        udpSessions = 0
        txBytes.set(0L)
        rxBytes.set(0L)
        tunnelStats = null
    }

    fun log(message: String) = synchronized(lock) {
        appendLogLocked(message)
    }

    fun debug(message: String) = synchronized(lock) {
        if (verboseLogging) appendLogLocked(message)
    }

    fun error(message: String) = synchronized(lock) {
        appendLogLocked("error: $message")
    }

    private fun appendLogLocked(message: String) {
        val time = timeFormat.format(Date())
        logs.addLast("[$time] $message")
        while (logs.size > MAX_LOGS) {
            logs.removeFirst()
        }
    }

    fun snapshot(): RuntimeSnapshot = synchronized(lock) {
        RuntimeSnapshot(
            vpnRunning = vpnRunning,
            webhookStatus = webhookStatus,
            mapping = mapping,
            tcpSessions = tcpSessions,
            udpSessions = udpSessions,
            txBytes = txBytes.get(),
            rxBytes = rxBytes.get(),
            tunnelStats = tunnelStats?.copyOf(),
            verboseLogging = verboseLogging,
            logs = logs.toList(),
        )
    }

    fun renderText(): String {
        val snap = snapshot()
        val tunnelLine = snap.tunnelStats?.let {
            if (it.size >= 4) {
                "hevtunnel packets/bytes tx=${it[0]}/${it[1]} rx=${it[2]}/${it[3]}"
            } else {
                "hevtunnel stats unavailable"
            }
        } ?: "hevtunnel stats unavailable"
        val mappingText = snap.mapping?.display() ?: "mapping: none"
        val logText = if (snap.logs.isEmpty()) "logs: none" else snap.logs.joinToString("\n")
        return buildString {
            appendLine("VPN: ${if (snap.vpnRunning) "running" else "stopped"}")
            appendLine("Webhook: ${snap.webhookStatus}")
            appendLine("TCP sessions: ${snap.tcpSessions}")
            appendLine("UDP sessions: ${snap.udpSessions}")
            appendLine("Proxy bytes: tx=${snap.txBytes} rx=${snap.rxBytes}")
            appendLine("Log mode: ${if (snap.verboseLogging) "verbose" else "performance"}")
            appendLine(tunnelLine)
            appendLine()
            appendLine("Current mapping")
            appendLine(mappingText)
            appendLine()
            appendLine("Log")
            append(logText)
        }
    }
}
