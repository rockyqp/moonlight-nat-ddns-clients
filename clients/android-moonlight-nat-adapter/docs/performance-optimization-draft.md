# Moonlight NAT Adapter Performance Optimization Draft

## Current Baseline

The working PoC path is:

```text
Moonlight
  -> Android VpnService TUN
  -> hevtunnel tun2socks
  -> local SOCKS5 server in Kotlin
  -> Kotlin TCP/UDP rewrite relay
  -> real Sunshine public host:mappedPort
```

This proves the product idea, but it adds more user-space work than IPv6 direct:

- TUN packet handling in hevtunnel.
- SOCKS5 TCP/UDP framing.
- Kotlin `DatagramSocket` forwarding for UDP.
- Synchronized counters and UI log refresh.

The observed extra latency of roughly 5-10 ms is plausible for this shape. The biggest controllable costs are the Kotlin UDP relay, SOCKS5 UDP wrapping, and runtime logging/UI churn.

## Goal

Reduce steady-state streaming latency and jitter while preserving the current user behavior:

- Moonlight keeps adding the fixed public-looking host IP.
- Webhook still supplies real public host and mapped Sunshine ports.
- Sunshine and Moonlight source code remain unchanged.
- VPS is still control-plane only, not a media relay.

## Option 1: Move UDP Relay from Kotlin to Native

### Feasibility

High.

The current relay already has a small, well-defined job:

- Read SOCKS5 UDP packets from hevtunnel.
- Parse the SOCKS5 UDP header.
- Rewrite `45.66.66.2:47998/47999/48000` to `realHost:mappedPort`.
- Send payload over a protected UDP socket.
- Wrap reverse packets back into SOCKS5 UDP format.

That can move to C/C++ via Android NDK without changing the VpnService or hevtunnel dependency.

### Expected Benefit

Medium.

This removes Java/Kotlin scheduling from the hot UDP path and avoids per-packet Kotlin object allocation (`DatagramPacket`, `InetSocketAddress`, `ByteArray.copyOfRange`, `ConcurrentHashMap` lookups). It should mainly reduce jitter and CPU wakeups. It may not eliminate all 5-10 ms, because hevtunnel + SOCKS5 framing still remain.

### Implementation Sketch

Add an NDK shared library:

```text
app/src/main/cpp/
  moonlight_nat_relay.cpp
  CMakeLists.txt
```

Expose JNI functions:

```kotlin
object NativeUdpRelay {
    external fun start(
        bindHost: String,
        virtualHost: String,
        mappingJson: String,
        protectFdCallbackToken: Long,
    ): Int

    external fun updateMapping(mappingJson: String)

    external fun stop()
}
```

More practical JNI API:

```kotlin
external fun startUdpRelay(
    virtualIp: String,
    realHost: String,
    udp47998: Int,
    udp47999: Int,
    udp48000: Int,
): Int

external fun updateUdpMapping(
    realHost: String,
    udp47998: Int,
    udp47999: Int,
    udp48000: Int,
)
```

The native relay should:

- Create one client-facing UDP socket bound to `0.0.0.0:0`.
- Return the port to Kotlin, so SOCKS5 UDP ASSOCIATE can advertise `127.0.0.1:port`.
- Call back to Kotlin or pass fd back to Kotlin for `VpnService.protect(int)`.
- Use `recvmsg` / `sendmsg` or `recvfrom` / `sendto`.
- Maintain a fixed table for the three Sunshine UDP ports instead of a generic map.
- Reuse packet buffers.

### Risks

- `VpnService.protect(int)` must be applied to native sockets, otherwise the relay can route into its own VPN and loop.
- JNI callback shape must be kept simple; passing raw socket fd to Kotlin for protect is safer than a complex callback from native.
- Native crash would kill the app process, so defensive bounds checks matter.

### Milestone

M1-native-socks5-udp:

- Keep hevtunnel and SOCKS5.
- Replace only Kotlin UDP relay with native UDP relay.
- Leave TCP in Kotlin because TCP is not the hot media path.

Acceptance:

- Pairing still works.
- Streaming works.
- App log shows no `UDP relay error`.
- Compare Moonlight latency against `0.1.6-poc` for 10 minutes.

## Option 3: Reduce Logs and UI Refresh

### Feasibility

Very high.

This is a safe incremental optimization and should be done before deeper native work. The current `AdapterStatus` synchronizes on every byte counter update and every log append. The UI renders the whole status text every second, including log history. During streaming, packet traffic is high enough that these operations can add avoidable CPU and GC pressure.

### Expected Benefit

Low to medium.

This probably will not reduce route latency by 5 ms alone, but it can reduce jitter and battery/CPU use. It also makes later benchmarking cleaner.

### Implementation Sketch

Add a performance mode:

```kotlin
enum class LogMode {
    Verbose,
    Performance,
}
```

Behavior:

- Verbose mode: current behavior.
- Performance mode:
  - Keep counters with `AtomicLong` instead of synchronized `addTxBytes/addRxBytes`.
  - Drop per-session UDP logs after the first route setup.
  - Keep only warning/error logs.
  - Refresh UI every 2-3 seconds or when a state changes.
  - Avoid rendering full mapping/log text every tick.

Minimal changes:

- Replace `txBytes/rxBytes` synchronized updates with `AtomicLong`.
- Add `AdapterStatus.logDebug()` and `AdapterStatus.logError()`.
- Gate hot-path logs.
- Add a UI toggle:

```text
[ ] Verbose logs
```

Default should be performance mode.

### Risks

- Less visibility while debugging.
- Need a one-tap way to re-enable verbose logs when a test fails.

### Milestone

M0-performance-mode:

- No protocol changes.
- No native code.
- Reduce hot-path synchronization/logging.

Acceptance:

- Streaming still works.
- UI remains usable.
- Log still records webhook updates, start/stop, and errors.

## Option 4: Bypass SOCKS5 UDP and Do TUN-Level NAT Rewrite

### Feasibility

Medium to high, but larger project.

This is the cleanest latency path, because it removes:

- SOCKS5 UDP ASSOCIATE control flow.
- SOCKS5 UDP packet headers.
- Kotlin/native SOCKS5 UDP parsing.
- One user-space UDP relay boundary.

But it means we either replace hevtunnel for UDP or fork/integrate a packet-processing layer before hevtunnel.

### Two Possible Designs

#### Design A: Hybrid TUN Split

Keep hevtunnel for TCP. Handle UDP ourselves at the TUN packet layer.

```text
TUN fd
  -> native packet loop
      TCP packets -> hevtunnel path
      UDP Sunshine packets -> direct NAT rewrite
```

Problem: one TUN fd cannot be safely consumed by both hevtunnel and our native loop unless we own the dispatcher. hevtunnel currently expects to own the TUN fd and block on it. So this probably requires replacing hevtunnel with a custom dispatcher or modifying/forking hevtunnel.

Verdict: possible, but invasive.

#### Design B: Replace tun2socks with a Small Sunshine-Specific NAT Stack

Because the target scope is tiny, implement only what is needed:

- Parse IPv4 packets from TUN.
- Handle UDP for `45.66.66.2:47998/47999/48000`.
- Rewrite destination IP/port to `realHost:mappedPort`.
- Recompute IPv4 and UDP checksums.
- Send via protected native UDP sockets.
- Reverse map incoming packets back to `45.66.66.2:originalPort`.
- For TCP, either:
  - keep a mature TCP stack, or
  - keep hevtunnel/SOCKS for TCP separately in a later architecture.

Problem: TCP still needs a mature TCP implementation. Moonlight pairing and launch control uses TCP, so UDP-only NAT is not enough unless TCP stays on hevtunnel.

Verdict: good long-term direction, but should not be first native milestone.

#### Design C: Fork hevtunnel/lwIP and Add Destination NAT Hook

hevtunnel already handles TUN and TCP/UDP forwarding in native C. Add a hook before SOCKS5 UDP forwarding:

```text
if packet.dst == virtualIp && packet.udp.dstPort in sunshineUdpPorts:
    rewrite to realHost:mappedUdpPort
    send direct protected UDP
else:
    existing hevtunnel behavior
```

This keeps mature TUN/lwIP handling and removes SOCKS5 only for Sunshine UDP. It is likely the best long-term architecture if we are comfortable carrying a small fork or contributing a hook upstream.

Verdict: best performance ceiling; medium maintenance cost.

### Expected Benefit

High.

This removes the most artificial part of the current PoC for media traffic: SOCKS5 UDP wrapping and local UDP relay. It should get closest to IPv4 direct NAT behavior. The remaining overhead is VpnService/TUN plus native packet processing.

### Risks

- More complex correctness: checksums, MTU, fragmentation, reverse mapping, socket protection.
- More maintenance if we fork hevtunnel.
- Android VPN fd lifecycle must be handled carefully.

### Milestone

M2-tun-udp-nat:

- Keep the current app UX and webhook model.
- Build a native UDP NAT path for the three Sunshine UDP ports.
- Keep TCP on hevtunnel until UDP path is proven.

Acceptance:

- Pairing works.
- Streaming works.
- Latency is closer to IPv6 direct than M1.
- No SOCKS5 UDP ASSOCIATE usage during streaming UDP.

## Recommended Order

1. M0-performance-mode
   - Cheapest.
   - Reduces measurement noise.
   - Useful even if later native work happens.

2. M1-native-socks5-udp
   - Best next real latency experiment.
   - Low architecture risk.
   - Keeps hevtunnel unchanged.

3. M2-tun-udp-nat
   - Highest performance ceiling.
   - Most engineering risk.
   - Do this only after measuring M0/M1.

## Benchmark Plan

Use the same Sunshine host, same Moonlight settings, same network, and same game/menu scene.

Record:

- Moonlight reported network latency.
- Frame drop/jitter signs.
- App CPU usage from Android developer tools or `adb shell top`.
- Whether VPN stop/start works reliably.
- App log errors.

Suggested test matrix:

```text
IPv6 direct baseline
0.1.6-poc current Kotlin UDP relay
M0 performance mode
M1 native SOCKS5 UDP relay
M2 TUN UDP NAT
```

Each run should last at least 5-10 minutes. Ignore the first 30 seconds because codec/session startup can distort latency.

## Notes from Current Code

Hot-path costs visible today:

- `DynamicSocksServer.relayClientUdp()` parses SOCKS5 UDP headers and copies payloads.
- `DynamicSocksServer.relayRemoteUdp()` copies payloads and rebuilds SOCKS5 UDP headers.
- `AdapterStatus.addTxBytes()` and `addRxBytes()` synchronize every update.
- `AdapterStatus.log()` synchronizes and formats timestamps.
- `MainActivity` re-renders the full text output every second.

## External References

- Android `VpnService` docs describe the core lifecycle: build the VPN interface with `Builder.establish()`, process packets from the returned fd, and close the fd when revoked/stopping.
- Android `ParcelFileDescriptor.close()` closes the underlying OS resources.
- HevSocks5Tunnel is a lightweight tun2socks implementation and supports redirecting TCP and UDP packets, including SOCKS5 UDP relay modes.
- HevSocks5Tunnel exposes `hev_socks5_tunnel_main_from_file(config_path, tun_fd)` and `hev_socks5_tunnel_quit()`, which matches the current Android wrapper model.
