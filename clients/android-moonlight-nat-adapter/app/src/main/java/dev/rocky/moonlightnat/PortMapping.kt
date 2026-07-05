package dev.rocky.moonlightnat

data class PortMapping(
    val host: String,
    val tcp: Map<Int, Int>,
    val udp: Map<Int, Int>,
    val fetchedAtMillis: Long,
    val sourceUrl: String,
) {
    fun display(): String {
        val tcpText = SunshinePorts.tcp.sorted().joinToString("\n") { port ->
            "TCP $port -> ${tcp[port]?.toString() ?: "missing"}"
        }
        val udpText = SunshinePorts.udp.sorted().joinToString("\n") { port ->
            "UDP $port -> ${udp[port]?.toString() ?: "missing"}"
        }
        return "host: $host\n$tcpText\n$udpText"
    }
}

enum class TransportProtocol {
    TCP,
    UDP,
}

data class RewriteTarget(
    val realHost: String,
    val realPort: Int,
    val virtualHost: String,
    val virtualPort: Int,
    val protocol: TransportProtocol,
)
