# STUNDDNS 跨平台串流客户端

支持 Android 和 Windows 的非官方 Moonlight NAT-DDNS 客户端项目，另含可选的 Android VPN Adapter 实验工具。

本仓库包含一套配合 `stunddns.top` 使用的 Moonlight/Sunshine NAT-DDNS 客户端改造。目标是在没有公网 IPv4 的家庭宽带、移动宽带或内网环境下，尽量通过路由器 Lucky 的 STUN 打洞能力建立直连串流，而不是让 VPS 中继视频流。

## 项目目的

标准 Moonlight 远程串流通常需要客户端能访问 Sunshine 的固定公网地址和固定端口。如果宽带没有公网 IPv4，或者公网端口会动态变化，普通端口转发就很难稳定使用。

本项目把这个问题拆成三层：

1. 路由器或内网设备上的 Lucky 负责 STUN 打洞，获取 Sunshine 端口当前对应的公网 IP 和公网端口。
2. 配套网站 `stunddns.top` 保存这些映射，并提供一个 NAT-DDNS URL。
3. 改造后的 Moonlight 客户端在连接前访问这个 URL，把 Sunshine 的逻辑端口替换成当前公网映射端口，再直接连接 Sunshine。

网站只负责保存和返回映射，不转发画面、音频或手柄数据。

## 仓库内容

```text
clients/
  moonlight-android/              # 推荐使用的 Android 客户端，直接内置 NAT-DDNS 支持
  android-moonlight-nat-adapter/  # 旧版 Android VPN Adapter PoC
  moonlight-qt/                   # Windows / PC 版 Moonlight 客户端
docs/
  build.md                        # 编译说明
  lucky-stun.md                   # Lucky、网站和客户端配合流程
  nat-ddns-api.md                 # 网站接口格式
```

## 源码和许可证声明

本仓库包含 Moonlight 官方开源客户端的修改版：

- `clients/moonlight-android` 基于 [moonlight-stream/moonlight-android](https://github.com/moonlight-stream/moonlight-android) 修改。
- `clients/moonlight-qt` 基于 [moonlight-stream/moonlight-qt](https://github.com/moonlight-stream/moonlight-qt) 修改。
- 两个 Moonlight 客户端目录内保留了上游 README、作者信息和 GPLv3 许可证文件。
- 本仓库发布完整修改源码，供用户按 GPLv3 获取、研究、修改和重新编译。
- 本项目不是 Moonlight 官方项目，也不代表 Moonlight/Sunshine 官方维护者。

更多说明见 [NOTICE.md](NOTICE.md)。

## 推荐使用方式

优先使用：

- Android：`clients/moonlight-android`
- Windows：`clients/moonlight-qt`

`clients/android-moonlight-nat-adapter` 是早期验证方案，它通过 Android VPN 接管官方 Moonlight 的流量，适合理解或测试思路，不是首选正式方案。

## 网站地址

配套网站：

```text
http://stunddns.top
```

客户端填写的是网站生成的 NAT-DDNS URL，格式类似：

```text
http://stunddns.top/api/moonlight/<用户名>/<设备名>
```

打开这个 URL 应该能看到 JSON 映射，例如：

```json
{
  "host": "203.0.113.10",
  "tcp": {
    "47984": 52084,
    "47989": 52089,
    "47990": 52090,
    "48010": 53010
  },
  "udp": {
    "47998": 52998,
    "47999": 52999,
    "48000": 53000
  }
}
```

## Lucky + STUN 打洞串流流程

1. 在 Sunshine 所在网络里部署 Lucky，常见位置是路由器、旁路由、NAS 或一台长期在线的内网设备。
2. 在 Lucky 中配置 STUN 打洞/端口映射，让 Sunshine 端口获得公网映射。
3. 需要映射的 Sunshine 端口：
   - TCP: `47984`, `47989`, `47990`, `48010`
   - UDP: `47998`, `47999`, `48000`
4. 让 Lucky 把当前映射同步到 `stunddns.top` 的对应条目。
5. 在网站里确认条目可访问，并复制 NAT-DDNS URL。
6. 在改造客户端里手动添加电脑时，直接粘贴 NAT-DDNS URL。
7. 客户端拉取映射后，会连接返回的 `host` 和映射端口。

更详细步骤见 [docs/lucky-stun.md](docs/lucky-stun.md)。

## Android 客户端使用

推荐使用 `clients/moonlight-android`。

1. 安装改造版 APK，或按 [docs/build.md](docs/build.md) 自行编译。
2. 打开 Moonlight。
3. 点击手动添加电脑。
4. 不输入普通 IP，直接输入网站生成的 NAT-DDNS URL：

   ```text
   http://stunddns.top/api/moonlight/<用户名>/<设备名>
   ```

5. 客户端会先请求映射，成功后按 Sunshine 正常流程配对、拉取应用列表、启动串流。

Debug 构建的包名是 `com.limelight.debug`，可以和官方 Moonlight 共存。正式分发前应使用自己的 applicationId 和签名。

## Windows 客户端使用

使用 `clients/moonlight-qt`。

1. 打开改造版 `Moonlight.exe`。
2. 在添加电脑/手动添加主机时，输入网站生成的 NAT-DDNS URL：

   ```text
   http://stunddns.top/api/moonlight/<用户名>/<设备名>
   ```

3. 客户端会拉取映射，并连接映射后的公网端口。
4. 如果 Lucky 重新打洞导致端口变化，可以在电脑条目的菜单中选择 `Refresh NAT-DDNS Mapping` 后再连接。

## Android NAT Adapter PoC

`clients/android-moonlight-nat-adapter` 是旧验证方案。它不修改官方 Moonlight，而是启动 Android VPN，把 Moonlight 访问虚拟主机 IP 的流量转写到网站返回的真实公网映射。

基本流程：

1. 安装并打开 Moonlight NAT Adapter。
2. 填入网站生成的 NAT-DDNS URL。
3. 点击 `Start VPN` 并同意 Android VPN 权限。
4. 打开官方 Moonlight，手动添加 Adapter 界面显示的虚拟主机 IP。

这个方案链路更长，调试价值大于正式使用价值。正式使用建议优先选择内置 NAT-DDNS 的 Android 客户端。

## 限制和注意事项

- 这不是内网穿透中继服务。网站/VPS 不转发串流流量，只保存和返回 Lucky 获取到的映射。
- 如果网络是对称 NAT、严格 NAT4、运营商 CGNAT、多层 NAT，或者 Lucky 无法获得稳定映射，可能无法使用。
- 如果公网映射端口在串流过程中变化，当前会话不会自动迁移，通常需要刷新映射并重新连接。
- Sunshine 主机防火墙、路由器防火墙、运营商策略都可能阻断连接。
- 推荐使用 HTTPS 部署正式服务；测试阶段可以使用 `http://stunddns.top`。
- 本方案目标是让客户端直连 Sunshine，实际延迟取决于两端网络、NAT 类型和运营商路径，不保证一定低于 IPv6 直连。

## 编译

编译步骤见 [docs/build.md](docs/build.md)。
