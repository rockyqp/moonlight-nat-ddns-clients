# Lucky STUN 打洞配合流程

本方案依赖 Lucky 在 Sunshine 所在网络侧获取公网端口映射，再把映射同步到 `stunddns.top`。客户端通过网站读取映射后，直接连接 Sunshine 的公网映射端口。

## 总体链路

```text
Sunshine 主机
  -> 局域网/路由器
  -> Lucky STUN 打洞
  -> stunddns.top 保存映射
  -> Moonlight 客户端拉取映射
  -> Moonlight 直连 Sunshine 公网映射端口
```

`stunddns.top` 不转发串流数据，它只提供控制面信息。

## Lucky 侧准备

1. 确认 Sunshine 在内网可以被 Moonlight 正常访问。
2. 在路由器、旁路由、NAS 或长期在线设备上部署 Lucky。
3. 在 Lucky 中启用 STUN 打洞或相同用途的公网端口映射功能。
4. 为 Sunshine 建立下面端口的映射：

   ```text
   TCP: 47984, 47989, 47990, 48010
   UDP: 47998, 47999, 48000
   ```

5. 配置 Lucky 把公网 IP 和映射端口上报到 `stunddns.top` 的对应设备条目。
6. 在浏览器打开网站生成的 NAT-DDNS URL，确认能看到 JSON 映射。

## 客户端侧使用

Android 和 Windows 改造客户端都使用同一个 URL：

```text
http://stunddns.top/api/moonlight/<用户名>/<设备名>
```

在客户端手动添加电脑时，直接粘贴这个 URL。不要把 URL 里的公网端口手动拆出来填进 Moonlight，客户端会自动解析和替换端口。

## 判断是否适合使用

更容易成功的网络：

- 家庭宽带有相对稳定的 NAT 映射。
- Lucky 能通过 STUN 获取外部端口。
- UDP 映射能维持到 Moonlight 客户端发起连接。

可能无法使用的网络：

- 对称 NAT。
- 严格 NAT4。
- 运营商 CGNAT。
- 多层 NAT 且上级 NAT 不允许外部打入。
- UDP 被运营商、路由器或防火墙限制。

如果只能配对但无法开始串流，重点检查 UDP `47998`、`47999`、`48000` 是否成功映射并可达。

## 端口变化

Lucky 重新打洞后，公网端口可能变化。客户端在连接前会刷新映射，但已经建立的串流会话不会自动迁移到新端口。

遇到端口变化时：

1. 在 Lucky 中确认映射已经刷新。
2. 在网站中确认 NAT-DDNS URL 返回的是新端口。
3. Windows 客户端可使用 `Refresh NAT-DDNS Mapping`。
4. 重新启动串流。
