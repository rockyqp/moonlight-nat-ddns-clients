# Android Moonlight NAT Adapter PoC

这个 Android Studio 项目用于验证：

- Moonlight 和 Sunshine 都不改源码。
- Android `VpnService` 接管 Moonlight 到虚拟 Sunshine IP 的流量。
- 成熟 tun2socks 内核把 TUN 里的 TCP/UDP 转成 SOCKS5。
- App 内置的动态 SOCKS5 代理按 webhook 映射把 `45.66.66.2:SunshinePort` 转到真实公网 `host:mappedPort`。
- VPS 只提供 webhook/control-plane，不中继视频流。

## 架构

```text
Moonlight
  -> Android VpnService route 10.66.66.2/32
  -> hevtunnel / HevSocks5Tunnel tun2socks
  -> local Dynamic SOCKS5 server
  -> webhook mapping
  -> real Sunshine public host:mappedPort
```

本 PoC 使用 `com.zaneschepke:hevtunnel:1.0.1`，AAR 内带 `libhev-socks5-tunnel.so`，App 不自行实现 TCP/IP 协议栈。Kotlin 代码只实现本地 SOCKS5 CONNECT / UDP ASSOCIATE 的目标重写和字节转发。

## Sunshine 端口

TCP:

- `47984`
- `47989`
- `47990`
- `48010`

UDP:

- `47998`
- `47999`
- `48000`

## Webhook 返回格式

```json
{
  "host": "1.2.3.4",
  "tcp": {
    "47984": 52184,
    "47989": 52189,
    "47990": 52190,
    "48010": 52210
  },
  "udp": {
    "47998": 52198,
    "47999": 52199,
    "48000": 52200
  }
}
```

App 每 5 秒刷新一次 webhook。连接建立时如果映射为空或过期，会同步再拉取一次。

## 运行方式

1. 用 Android Studio 打开 `android-moonlight-nat-adapter` 目录。
2. 等 Gradle sync 下载 Android Gradle Plugin、Kotlin 和 `hevtunnel` 依赖。
3. 连接 Android 设备或启动模拟器。
4. Run `app`。
5. 在 App 中填写：
   - `Webhook URL`
   - `Virtual Sunshine IP`，默认 `10.66.66.2`
6. 点 `Start VPN`，同意 Android VPN 权限。
7. 打开 Moonlight，手动添加主机 IP：`45.66.66.2`。

## APK 编译

Android Studio:

```text
Build -> Build Bundle(s) / APK(s) -> Build APK(s)
```

命令行:

```powershell
cd C:\Users\Rocky\Documents\stun\android-moonlight-nat-adapter
gradle :app:assembleDebug
```

输出位置:

```text
app/build/outputs/apk/debug/app-debug.apk
```

如果本机没有系统 Gradle，可以在 Android Studio 中打开项目后使用 IDE 自带 Gradle 运行配置，或先生成 wrapper：

```powershell
gradle wrapper --gradle-version 8.13
.\gradlew.bat :app:assembleDebug
```

## 日志含义

主界面实时显示：

- VPN 状态
- Webhook 状态
- 当前映射表
- TCP 会话数量
- UDP 会话数量
- 代理层收发字节数
- hevtunnel TUN 统计
- 连接和错误日志

## 当前 PoC 边界

- 只路由 `Virtual Sunshine IP/32`，默认是 `45.66.66.2/32`。这是给 Moonlight 填写的公网形态假地址，VPN 会在本机拦截它。
- 只允许上面列出的 Sunshine TCP/UDP 端口。
- UDP SOCKS5 relay 使用 `udp` 模式，不做 UDP-in-TCP。
- 第一阶段聚焦“Moonlight 手动添加 `10.66.66.2` 并走真实公网端口映射”。自动 LAN/mDNS 广播发现还没有伪造响应。
- webhook 建议用 HTTPS；如果使用 HTTP，Android 明文策略可能需要后续按域名补 `networkSecurityConfig`。
