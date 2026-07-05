package dev.rocky.moonlightnat

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class WebhookMappingProvider(
    private val webhookUrl: String,
    private val virtualHost: String,
    private val pollIntervalMillis: Long = 5_000L,
    private val onMappingUpdated: ((PortMapping) -> Unit)? = null,
) {
    private val lock = Object()
    private var executor: ScheduledExecutorService? = null
    private var latest: PortMapping? = null
    @Volatile private var stopped = false

    fun start() {
        stopped = false
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "WebhookMappingProvider").apply { isDaemon = true }
        }
        executor?.scheduleWithFixedDelay(
            { refreshSafely() },
            0L,
            pollIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        stopped = true
        executor?.shutdownNow()
        executor = null
    }

    fun resolve(protocol: TransportProtocol, requestedHost: String, requestedPort: Int): RewriteTarget {
        if (requestedHost != virtualHost) {
            throw IllegalArgumentException("reject $protocol target $requestedHost:$requestedPort; expected $virtualHost")
        }
        val allowed = if (protocol == TransportProtocol.TCP) SunshinePorts.tcp else SunshinePorts.udp
        if (requestedPort !in allowed) {
            throw IllegalArgumentException("reject $protocol port $requestedPort; not a Sunshine PoC port")
        }

        val mapping = currentMapping()
        val realPort = when (protocol) {
            TransportProtocol.TCP -> mapping.tcp[requestedPort]
            TransportProtocol.UDP -> mapping.udp[requestedPort]
        } ?: throw IllegalStateException("webhook mapping missing $protocol $requestedPort")

        return RewriteTarget(
            realHost = mapping.host,
            realPort = realPort,
            virtualHost = virtualHost,
            virtualPort = requestedPort,
            protocol = protocol,
        )
    }

    private fun currentMapping(): PortMapping {
        val cached = synchronized(lock) { latest }
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMillis < pollIntervalMillis * 2) {
            return cached
        }
        refreshSafely()
        return synchronized(lock) { latest }
            ?: throw IllegalStateException("no webhook mapping available")
    }

    private fun refreshSafely() {
        if (stopped || webhookUrl.isBlank()) {
            AdapterStatus.setWebhookStatus("webhook URL is empty")
            return
        }
        try {
            val mapping = fetch()
            synchronized(lock) {
                latest = mapping
            }
            AdapterStatus.setMapping(mapping)
            AdapterStatus.setWebhookStatus("ok, updated ${ageText(mapping.fetchedAtMillis)}")
            onMappingUpdated?.invoke(mapping)
            AdapterStatus.debug("webhook updated: ${mapping.host}")
        } catch (error: Exception) {
            AdapterStatus.setWebhookStatus("error: ${error.message}")
            AdapterStatus.error("webhook error: ${error.message}")
        }
    }

    private fun fetch(): PortMapping {
        val connection = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream ?: connection.inputStream)).use { it.readText() }
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status: $body")
            }
            val json = JSONObject(body)
            val host = json.getString("host")
            return PortMapping(
                host = host,
                tcp = parsePorts(json.getJSONObject("tcp"), SunshinePorts.tcp),
                udp = parsePorts(json.getJSONObject("udp"), SunshinePorts.udp),
                fetchedAtMillis = System.currentTimeMillis(),
                sourceUrl = webhookUrl,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePorts(json: JSONObject, expectedPorts: Set<Int>): Map<Int, Int> {
        return expectedPorts.associateWith { port ->
            json.getInt(port.toString()).also { mapped ->
                require(mapped in 1..65535) { "invalid mapped port for $port: $mapped" }
            }
        }
    }

    private fun ageText(timestamp: Long): String {
        val seconds = ((System.currentTimeMillis() - timestamp) / 1000L).coerceAtLeast(0L)
        return if (seconds == 0L) "now" else "${seconds}s ago"
    }
}
