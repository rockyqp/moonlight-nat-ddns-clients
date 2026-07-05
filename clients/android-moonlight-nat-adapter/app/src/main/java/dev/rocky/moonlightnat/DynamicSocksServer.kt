package dev.rocky.moonlightnat

import android.net.VpnService
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DynamicSocksServer(
    private val vpnService: VpnService,
    private val mappingProvider: WebhookMappingProvider,
    private val virtualHost: String,
) {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "DynamicSocksServer").apply { isDaemon = true }
    }
    private val tcpSessions = AtomicInteger(0)
    private val udpSessions = AtomicInteger(0)
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        check(!running) { "SOCKS server already running" }
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        running = true
        executor.execute { acceptLoop() }
        AdapterStatus.debug("SOCKS5 server listening on 127.0.0.1:$port")
        return port
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
        AdapterStatus.setTcpSessions(0)
        AdapterStatus.setUdpSessions(0)
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                executor.execute { handleClient(client) }
            } catch (error: IOException) {
                if (running) {
                    AdapterStatus.error("SOCKS accept error: ${error.message}")
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use {
            try {
                client.tcpNoDelay = true
                val input = DataInputStream(client.getInputStream())
                val output = client.getOutputStream()
                negotiate(input, output)
                val request = readRequest(input)
                when (request.command) {
                    SocksCommand.CONNECT -> handleConnect(client, output, request)
                    SocksCommand.UDP_ASSOCIATE -> handleUdpAssociate(client, output)
                    else -> writeReply(output, SocksReply.COMMAND_NOT_SUPPORTED, InetAddress.getByName("0.0.0.0"), 0)
                }
            } catch (error: Exception) {
                AdapterStatus.error("SOCKS client error: ${error.message}")
            }
        }
    }

    private fun negotiate(input: DataInputStream, output: OutputStream) {
        val version = input.readUnsignedByte()
        require(version == 5) { "unsupported SOCKS version $version" }
        val methodCount = input.readUnsignedByte()
        repeat(methodCount) { input.readUnsignedByte() }
        output.write(byteArrayOf(5, 0))
        output.flush()
    }

    private fun readRequest(input: DataInputStream): SocksRequest {
        val version = input.readUnsignedByte()
        require(version == 5) { "unsupported request version $version" }
        val command = SocksCommand.from(input.readUnsignedByte())
        input.readUnsignedByte()
        val address = readAddress(input)
        val port = input.readUnsignedShort()
        return SocksRequest(command, address, port)
    }

    private fun handleConnect(client: Socket, clientOutput: OutputStream, request: SocksRequest) {
        val target = try {
            mappingProvider.resolve(TransportProtocol.TCP, request.host, request.port)
        } catch (error: Exception) {
            AdapterStatus.error("TCP rewrite failed: ${error.message}")
            writeReply(clientOutput, SocksReply.CONNECTION_NOT_ALLOWED, InetAddress.getByName("0.0.0.0"), 0)
            return
        }

        val remote = Socket()
        var countedSession = false
        try {
            vpnService.protect(remote)
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(target.realHost, target.realPort), 10_000)
            writeReply(clientOutput, SocksReply.SUCCEEDED, remote.localAddress, remote.localPort)
            val active = tcpSessions.incrementAndGet()
            countedSession = true
            AdapterStatus.setTcpSessions(active)
            AdapterStatus.debug("TCP ${target.virtualHost}:${target.virtualPort} -> ${target.realHost}:${target.realPort}")
            spliceTcp(client, remote)
        } catch (error: Exception) {
            AdapterStatus.error("TCP connect failed: ${target.realHost}:${target.realPort}: ${error.message}")
            runCatching {
                writeReply(clientOutput, SocksReply.HOST_UNREACHABLE, InetAddress.getByName("0.0.0.0"), 0)
            }
        } finally {
            runCatching { remote.close() }
            if (countedSession) {
                val active = tcpSessions.decrementAndGet().coerceAtLeast(0)
                AdapterStatus.setTcpSessions(active)
            }
        }
    }

    private fun spliceTcp(client: Socket, remote: Socket) {
        val done = CountDownLatch(2)
        val clientToRemote = thread(name = "socks-tcp-c2r", isDaemon = true) {
            copyBytes(client.getInputStream(), remote.getOutputStream(), true)
            runCatching { remote.shutdownOutput() }
            done.countDown()
        }
        val remoteToClient = thread(name = "socks-tcp-r2c", isDaemon = true) {
            copyBytes(remote.getInputStream(), client.getOutputStream(), false)
            runCatching { client.shutdownOutput() }
            done.countDown()
        }
        done.await()
        clientToRemote.join(100)
        remoteToClient.join(100)
    }

    private fun copyBytes(input: InputStream, output: OutputStream, outbound: Boolean) {
        val buffer = ByteArray(32 * 1024)
        while (running) {
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (read <= 0) break
            try {
                output.write(buffer, 0, read)
                output.flush()
            } catch (_: IOException) {
                break
            }
            if (outbound) AdapterStatus.addTxBytes(read.toLong()) else AdapterStatus.addRxBytes(read.toLong())
        }
    }

    private fun handleUdpAssociate(controlSocket: Socket, controlOutput: OutputStream) {
        val udpSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
        }
        vpnService.protect(udpSocket)
        val active = udpSessions.incrementAndGet()
        AdapterStatus.setUdpSessions(active)
        AdapterStatus.debug("UDP associate on 0.0.0.0:${udpSocket.localPort}, advertised as 127.0.0.1")
        writeReply(controlOutput, SocksReply.SUCCEEDED, InetAddress.getByName("127.0.0.1"), udpSocket.localPort)

        val udpThread = thread(name = "socks-udp-relay", isDaemon = true) {
            udpRelayLoop(udpSocket)
        }
        try {
            val buffer = ByteArray(1)
            while (running && controlSocket.getInputStream().read(buffer) >= 0) {
                // The TCP control connection keeps the SOCKS5 UDP association alive.
            }
        } catch (_: IOException) {
        } finally {
            runCatching { udpSocket.close() }
            udpThread.join(500)
            val remaining = udpSessions.decrementAndGet().coerceAtLeast(0)
            AdapterStatus.setUdpSessions(remaining)
        }
    }

    private fun udpRelayLoop(udpSocket: DatagramSocket) {
        val buffer = ByteArray(65_535)
        val reverseMap = ConcurrentHashMap<SocketAddress, RewriteTarget>()
        var clientAddress: InetSocketAddress? = null
        while (running && !udpSocket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udpSocket.receive(packet)
                val sender = InetSocketAddress(packet.address, packet.port)
                if (packet.address.isLoopbackAddress) {
                    clientAddress = sender
                    relayClientUdp(udpSocket, packet, reverseMap)
                } else {
                    relayRemoteUdp(udpSocket, packet, clientAddress, reverseMap)
                }
            } catch (error: IOException) {
                if (running && !udpSocket.isClosed) {
                    AdapterStatus.error("UDP relay error: ${error.message}")
                }
            } catch (error: Exception) {
                AdapterStatus.error("UDP relay error: ${error.message}")
            }
        }
    }

    private fun relayClientUdp(
        udpSocket: DatagramSocket,
        packet: DatagramPacket,
        reverseMap: ConcurrentHashMap<SocketAddress, RewriteTarget>,
    ) {
        val datagram = parseUdpPacket(packet.data, packet.offset, packet.length)
        val target = mappingProvider.resolve(TransportProtocol.UDP, datagram.host, datagram.port)
        val remote = InetSocketAddress(target.realHost, target.realPort)
        if (reverseMap.put(remote, target) == null) {
            AdapterStatus.debug("UDP ${target.virtualHost}:${target.virtualPort} -> ${target.realHost}:${target.realPort}")
        }
        val outbound = DatagramPacket(datagram.payload, datagram.payload.size, remote)
        udpSocket.send(outbound)
        AdapterStatus.addTxBytes(datagram.payload.size.toLong())
    }

    private fun relayRemoteUdp(
        udpSocket: DatagramSocket,
        packet: DatagramPacket,
        clientAddress: InetSocketAddress?,
        reverseMap: ConcurrentHashMap<SocketAddress, RewriteTarget>,
    ) {
        if (clientAddress == null) return
        val remote = InetSocketAddress(packet.address, packet.port)
        val target = reverseMap[remote]
            ?: reverseMap.entries.firstOrNull { it.key is InetSocketAddress && (it.key as InetSocketAddress).port == packet.port }?.value
            ?: return
        val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        val wrapped = buildUdpPacket(target.virtualHost, target.virtualPort, payload)
        udpSocket.send(DatagramPacket(wrapped, wrapped.size, clientAddress))
        AdapterStatus.addRxBytes(payload.size.toLong())
    }

    private fun parseUdpPacket(data: ByteArray, offset: Int, length: Int): SocksUdpDatagram {
        require(length >= 10) { "UDP packet too short" }
        var cursor = offset
        require(data[cursor].toInt() == 0 && data[cursor + 1].toInt() == 0) { "bad UDP RSV" }
        cursor += 2
        val frag = data[cursor++].toInt() and 0xff
        require(frag == 0) { "fragmented UDP is not supported" }
        val host = readAddress(data, cursor)
        cursor = host.nextOffset
        val port = ((data[cursor].toInt() and 0xff) shl 8) or (data[cursor + 1].toInt() and 0xff)
        cursor += 2
        val payload = data.copyOfRange(cursor, offset + length)
        return SocksUdpDatagram(host.host, port, payload)
    }

    private fun buildUdpPacket(host: String, port: Int, payload: ByteArray): ByteArray {
        val address = InetAddress.getByName(host)
        require(address is Inet4Address) { "only IPv4 virtual host is supported: $host" }
        val header = ByteArray(10)
        header[0] = 0
        header[1] = 0
        header[2] = 0
        header[3] = 1
        val hostBytes = address.address
        System.arraycopy(hostBytes, 0, header, 4, 4)
        header[8] = (port shr 8).toByte()
        header[9] = port.toByte()
        return header + payload
    }

    private fun readAddress(input: DataInputStream): String {
        return when (val atyp = input.readUnsignedByte()) {
            1 -> {
                val bytes = ByteArray(4)
                input.readFully(bytes)
                InetAddress.getByAddress(bytes).hostAddress ?: ""
            }
            3 -> {
                val size = input.readUnsignedByte()
                val bytes = ByteArray(size)
                input.readFully(bytes)
                bytes.toString(Charsets.UTF_8)
            }
            4 -> {
                val bytes = ByteArray(16)
                input.readFully(bytes)
                InetAddress.getByAddress(bytes).hostAddress ?: ""
            }
            else -> throw IllegalArgumentException("unsupported ATYP $atyp")
        }
    }

    private fun readAddress(data: ByteArray, offset: Int): ParsedAddress {
        var cursor = offset
        return when (val atyp = data[cursor++].toInt() and 0xff) {
            1 -> {
                val bytes = data.copyOfRange(cursor, cursor + 4)
                cursor += 4
                ParsedAddress(InetAddress.getByAddress(bytes).hostAddress ?: "", cursor)
            }
            3 -> {
                val size = data[cursor++].toInt() and 0xff
                val host = data.copyOfRange(cursor, cursor + size).toString(Charsets.UTF_8)
                cursor += size
                ParsedAddress(host, cursor)
            }
            4 -> {
                val bytes = data.copyOfRange(cursor, cursor + 16)
                cursor += 16
                ParsedAddress(InetAddress.getByAddress(bytes).hostAddress ?: "", cursor)
            }
            else -> throw IllegalArgumentException("unsupported UDP ATYP $atyp")
        }
    }

    private fun writeReply(output: OutputStream, reply: SocksReply, bindAddress: InetAddress, bindPort: Int) {
        val address = if (bindAddress is Inet4Address) bindAddress.address else byteArrayOf(0, 0, 0, 0)
        output.write(byteArrayOf(5, reply.code.toByte(), 0, 1))
        output.write(address)
        output.write(byteArrayOf((bindPort shr 8).toByte(), bindPort.toByte()))
        output.flush()
    }
}

private data class SocksRequest(
    val command: SocksCommand,
    val host: String,
    val port: Int,
)

private enum class SocksCommand(val code: Int) {
    CONNECT(1),
    BIND(2),
    UDP_ASSOCIATE(3),
    UNKNOWN(-1);

    companion object {
        fun from(value: Int): SocksCommand = entries.firstOrNull { it.code == value } ?: UNKNOWN
    }
}

private enum class SocksReply(val code: Int) {
    SUCCEEDED(0),
    CONNECTION_NOT_ALLOWED(2),
    HOST_UNREACHABLE(4),
    COMMAND_NOT_SUPPORTED(7),
}

private data class SocksUdpDatagram(
    val host: String,
    val port: Int,
    val payload: ByteArray,
)

private data class ParsedAddress(
    val host: String,
    val nextOffset: Int,
)
