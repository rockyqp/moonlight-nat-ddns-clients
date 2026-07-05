package dev.rocky.moonlightnat

import android.net.VpnService

object NativeTunNat {
    init {
        System.loadLibrary("moonlightnat")
    }

    external fun start(
        tunFd: Int,
        vpnService: VpnService,
        vpnAddress: String,
        virtualIp: String,
    ): Int

    external fun updateMapping(
        realHost: String,
        udp47998: Int,
        udp47999: Int,
        udp48000: Int,
    ): Boolean

    external fun stop()
}
