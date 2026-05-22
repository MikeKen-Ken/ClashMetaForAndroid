# Clash 配置：请求链路与 DNS 说明

> 基于 `config/new-clash-config.yaml` + `config/custom-clash-script.js` 整理。  
> 远程规则集（`ruleset/*.mrs`、自定义 `reject`/`wl-dir`/`dir` 等 `.mrs`）是否包含具体域名，需以实际 ruleset 与运行日志为准。

---

## 1. 配置组成


| 文件                       | 作用                                                       |
| ------------------------ | -------------------------------------------------------- |
| `new-clash-config.yaml`  | 基础：TUN、DNS、Sniffer、全局选项                                  |
| `custom-clash-script.js` | 生成 `proxy-groups`、`rule-providers`、`rules`               |
| 远程 ruleset               | DustinWin `ruleset_geodata` + `MikeKen-Ken/custome-rule` |


最终生效配置 = 订阅内容经脚本改写后再与 yaml 合并（以客户端实际加载为准）。

---

## 2. 总体架构

```
应用
  │
  ├─ DNS 查询 ──► TUN dns-hijack (53) ──► 内置 DNS ([::]:1053)
  │
  └─ TCP/UDP 连接 ──► TUN (newsilence, auto-route, strict-route)
                        │
                        ▼
                  规则匹配 (mode: rule)
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
       REJECT        DIRECT         代理策略组
```

### 2.1 关键全局项


| 项                     | 值               | 含义                    |
| --------------------- | --------------- | --------------------- |
| `mode`                | `rule`          | 按规则选策略组               |
| `enhanced-mode`       | `fake-ip`       | DNS 增强模式为假 IP         |
| `fake-ip-range`       | `198.18.0.1/16` | 假 IP 池                |
| `fake-ip-filter-mode` | `rule`          | 假/真 IP 由独立 DNS 规则决定   |
| `store-fake-ip`       | `true`          | 假 IP 映射可持久化           |
| `sniffer`             | 开启              | 可从 TLS/HTTP/QUIC 还原域名 |
| `fallback`            | 已注释             | 未启用 fallback 分流       |


---

## 3. DNS：核心机制（必读）

### 3.1 两套互不相同的「规则」


| 规则集              | 配置位置                 | 作用阶段       | 作用                          |
| ---------------- | -------------------- | ---------- | --------------------------- |
| `fake-ip-filter` | `dns.fake-ip-filter` | **DNS 查询** | 决定返回 `fake-ip` 还是 `real-ip` |
| `rules`（脚本生成）    | `config.rules`       | **连接路由**   | 决定 REJECT / DIRECT / 哪个策略组  |


同一域名可以：DNS 走 fake-ip，连接仍走 DIRECT（如 `cn`）；二者独立判断。

当前 DNS 过滤规则：

```yaml
fake-ip-filter:
  - RULE-SET,fakeip-filter,real-ip
  - MATCH,fake-ip
```

### 3.2 fake-ip：App 查 DNS 时发生什么

对命中 `MATCH,fake-ip` 的域名：

1. **不会**用 `nameserver`（1.1.1.1 / DoH 等）解析目标站真实 IP。
2. 从 `198.18.0.0/16` 池分配假 IP，建立 `域名 ↔ 假 IP` 映射。
3. 将假 IP 返回给应用。

### 3.3 连接建立后：谁解析目标站


| 出口                            | 典型行为                                                                           |
| ----------------------------- | ------------------------------------------------------------------------------ |
| **代理（TCP）**                   | 假 IP → 还原域名 → 清空 `DstIP` → 协议头带 **域名**（如 SOCKS/Trojan/VMess）→ **机场节点侧 DNS 解析** |
| **DIRECT**                    | 本地用 `direct-nameserver` 解析真实 IP → 本机网卡直连                                       |
| **real-ip**（fakeip-filter 命中） | DNS 阶段即返回真实 IP；直连类走 `direct-nameserver`                                        |
| **UDP 走代理**                   | 多数协议会先 **本地** `ResolveUDP`，再向节点发 **IP**（与 TCP 传域名不同）                           |


### 3.4 本地 DNS 上游分工


| 配置项                       | 用途                                                                      |
| ------------------------- | ----------------------------------------------------------------------- |
| `default-nameserver`      | 解析 DNS 服务器自身域名（如 DoH 主机名）                                               |
| `nameserver`              | 默认解析：real-ip 域名、规则触发的 `ResolveIP`、节点域名（未配 `proxy-server-nameserver` 时）等 |
| `direct-nameserver`       | **DIRECT 出口**相关域名的解析（阿里/腾讯 DoH 等）                                       |
| `proxy-server-nameserver` | **未配置**；节点域名不走单独通道                                                      |
| `respect-rules`           | **未配置**（默认 false）；DNS 查询本身不按路由走代理                                       |
| `fallback`                | **已注释**，无 fallback-filter 境内外分流                                         |


---

## 4. 策略组（脚本生成）


| 显示名                                   | 来源/类型                  | 说明                                   |
| ------------------------------------- | ---------------------- | ------------------------------------ |
| `🔀`                                  | 原「🚀 节点选择」→ `fallback` | 测 `gstatic.com/generate_204`，自动选可用节点 |
| `🚫 🇭🇰`                             | 从首组剔除含 `🇭🇰` 的节点      | 排除香港                                 |
| `🇭🇰`                                | 仅含 `🇭🇰` 节点           | 香港专用                                 |
| `🔀 UDP` / `🚫 🇭🇰 UDP` / `🇭🇰 UDP` | 上述组的 UDP 子集            | 仅 `udp !== false` 的节点                |
| `⬆️`                                  | 原「🎯 全球直连」→ `select`   | 直连                                   |
| `↩️`                                  | 原「🐟 漏网之鱼」             | TCP 漏网兜底                             |
| `↩️ UDP`                              | 漏网 UDP 兜底              | 子链指向各 `*_UDP` 组                      |


---

## 5. 路由规则顺序（自上而下）

脚本 `config.rules` 顺序（摘要）：

1. `reject` → **REJECT**
2. `proc-rej` → **REJECT**
3. `wl-dir` → **⬆️ DIRECT**
4. UDP + `wl-pxy` → **🔀 UDP**；否则 `wl-pxy` → **🔀**
5. `proc-wl-dir` → **⬆️ DIRECT**（进程白名单直连）
6. `ads` → **REJECT**
7. `private` / `privateip`（no-resolve）→ **⬆️**
8. `proc-dir` → **⬆️ DIRECT**
9. UDP + `proc-pxy` → **🔀 UDP**；否则 `proc-pxy` → **🔀**
10. `applications` → **⬆️**
11. `hk` → **🇭🇰**（UDP 走 **🇭🇰 UDP**）
12. `nohk` → **🚫 🇭🇰**（UDP 走 **🚫 🇭🇰 UDP**）
13. `dir` → **⬆️**
14. `pxy` → **🔀**（UDP 走 **🔀 UDP**）
15. `ai` → **🚫 🇭🇰**（UDP 走 **🚫 🇭🇰 UDP**）
16. `games-cn` / `microsoft-cn` / `apple-cn` / `trackerslist` / `cn` / `cnip` → **⬆️**
17. `NETWORK,UDP` → **↩️ UDP**
18. `MATCH` → **↩️ FALLBACK**

---

## 6. UDP 专题

UDP 与 TCP 共用 TUN、DNS（fake-ip）、`preHandleMetadata`（假 IP 还原域名），但在**规则匹配**和**代理出站**上与 TCP 有明显差异。本节专门说明。

### 6.1 为何脚本要单独写 `NETWORK,UDP` 规则？

Mihomo **没有**「协议 + 域名」一体的 `PROTOCOL` 规则类型。若只写：

```text
RULE-SET,pxy,🔀
```

则 **TCP 和 UDP 都会** 命中同一条，且都进 **🔀**（含 `udp: false` 的节点）。

脚本的做法是「拆成两条」：

| 流量 | 规则写法 | 策略组 |
|------|----------|--------|
| UDP | `AND,((NETWORK,UDP),(RULE-SET,xxx)),🔀 UDP` | 仅含 `udp !== false` 的节点 |
| TCP | `RULE-SET,xxx,🔀` | 完整节点列表 |

**没有**写 `AND,NETWORK,UDP` 的规则（如 `cn`、`private`、`dir`）对 **TCP / UDP 一视同仁**，命中后走同一策略组（多为 **⬆️ DIRECT**）。

### 6.2 本配置中与 UDP 相关的规则一览

| 规则 | UDP 命中时 | TCP 命中时 |
|------|------------|------------|
| `wl-pxy` | **🔀 UDP** | **🔀** |
| `proc-pxy` | **🔀 UDP** | **🔀** |
| `hk` | **🇭🇰 UDP** | **🇭🇰** |
| `nohk` | **🚫 🇭🇰 UDP** | **🚫 🇭🇰** |
| `pxy` | **🔀 UDP** | **🔀** |
| `ai` | **🚫 🇭🇰 UDP** | **🚫 🇭🇰** |
| `cn` / `private` / … | 同左（无 UDP 分支） | **⬆️** 等 |
| 以上皆未命中 | **`NETWORK,UDP` → ↩️ UDP** | **`MATCH` → ↩️** |

要点：

- **漏网 TCP** 走 `MATCH → ↩️`（select：`🔀` / `🚫🇭🇰` / `🇭🇰` / `DIRECT`）。
- **漏网 UDP** 在 `MATCH` **之前** 被 `NETWORK,UDP` 截获 → **↩️ UDP**（select：`🔀 UDP` / `🚫🇭🇰 UDP` / `🇭🇰 UDP` / `DIRECT`）。
- 同一域名（如国外游戏）可能出现：**TCP 与 UDP 进不同策略组**（若 TCP 落 `MATCH` 而 UDP 落 `NETWORK,UDP`）。

### 6.3 `*_UDP` 策略组怎么来的？

脚本从 **🔀 / 🚫🇭🇰 / 🇭🇰** 复制一份，并：

1. 改名为 `🔀 UDP` 等；
2. 从列表中 **剔除** 订阅里 `udp: false` 的节点；
3. 强制 `"disable-udp": false`。

**↩️ UDP** 由 **↩️** 的子项映射而来（`🔀`→`🔀 UDP`，`🚫🇭🇰`→`🚫🇭🇰 UDP`，`🇭🇰`→`🇭🇰 UDP`，其余如 `DIRECT` 不变）。

若过滤后为空，脚本会打 warn 并 **回退为未过滤列表**（避免无节点可选）。

### 6.4 UDP 端到端链路（通用）

```text
应用发 UDP 包（目标多为 198.18.x.x:port，或真实 IP）
        │
        ▼
TUN 截获 ──► 按五元组等生成 NAT 会话 key
        │
        ▼
首包（新建会话）:
  ├─ 可选 UDPSniff（如 QUIC 443，尝试还原域名，改善规则匹配）
  ├─ preHandleMetadata：假 IP → 填回 Host，清空 DstIP（fake-ip）
  ├─ resolveMetadata：按 rules 选 REJECT / DIRECT / 代理组
  └─ proxy.ListenPacketContext：建立到 DIRECT 或代理节点的 UDP 通道
        │
后续包：同一会话走 NAT 映射，可缓存「域名 → 已解析 DstIP」
        │
        ▼
回包 ──► WriteBack ──► 应用
```

与 `new-clash-config.yaml` 相关：

| 项 | 值 | 含义 |
|----|-----|------|
| `tun.udp-timeout` | `300` | NAT 会话空闲约 300s 后回收 |
| `tun.endpoint-independent-nat` | `true` | 有利于部分 P2P / 游戏 NAT 行为 |

### 6.5 UDP 的 DNS 阶段（与 TCP 相同）

应用若先对域名做 DNS（再发 UDP）：

- 仍走 **fake-ip-filter** / **fake-ip** 逻辑，**不会**在查询阶段为 fake-ip 域名解析真实 IP。
- App 收到的仍是 **198.18.x.x**，随后向该地址发 UDP。

若应用 **不查 DNS、直接向 IP 发 UDP**：

- 无 DNS 阶段；靠 **fake-ip 反查** 或 **UDPSniff** 得到域名后再匹配规则。

### 6.6 出站：谁解析目标？（UDP 与 TCP 对比）

| 出口 | TCP（典型代理） | UDP（典型 SS/VMess/Trojan 等） |
|------|-----------------|--------------------------------|
| **代理** | 协议头常带 **域名** → **节点侧 DNS** | 出站前多会 **`ResolveUDP`（本地 nameserver）** → 向节点发 **目标 IP** |
| **DIRECT** | `direct-nameserver` 解析后本机 TCP | `DirectHostResolver` 解析后本机 `ListenPacket` |

内核逻辑摘要：

- 代理 `ListenPacketContext` 里常先 `ResolveUDP`（`resolver.DefaultResolver` → 你的 `nameserver`）。
- `packetSender.processPacket` 中若仍有 `Host` 无 `DstIP`，会再次 `ResolveUDP`。
- **DIRECT** 的 UDP 用 **`direct-nameserver`** 解析（与 TCP 直连一致）。

因此：

> **UDP 走代理时，目标站解析多在本地完成，再把 IP 交给机场；TCP 走代理时，目标站解析多在机场完成。**  
> 这是协议实现差异，不是配置写错。

例外（以订阅节点为准）：

- **UDP over TCP（UoT）**：先建 TCP，再在内层传 UDP，仍可能在本地 `ResolveUDP` 后传 IP。
- 部分新协议实现可能不同，以节点类型为准。

### 6.7 分场景 UDP 示例

#### A. 国内应用 UDP（域名在 `cn`，如部分国内 SDK）

```text
DNS（若查询）→ fake-ip 或 real-ip
规则 → RULE-SET,cn → ⬆️ DIRECT（无 UDP 专用分支）
出站 → direct-nameserver 解析 → 本机 UDP 直连
```

#### B. 国外游戏 / VoIP（域名不在 cn，未命中自定义规则集）

```text
DNS → fake-ip（198.18.x.x）
规则 → NETWORK,UDP → ↩️ UDP（手动或默认选 🔀 UDP 等）
出站 → 本地 ResolveUDP(nameserver) → 代理节点 → 目标 IP:port
```

与同一域名的 **TCP 浏览器流量** 对比：TCP 可能 `MATCH → ↩️ → 🔀`，且 TCP 传域名、节点解析——**路径不必相同**。

#### C. `pxy` 中的域名（仅 UDP 流量）

```text
规则 → AND,(UDP + pxy) → 🔀 UDP
出站 → 仅从支持 UDP 的节点中选择 → 本地解析 IP → 节点转发
```

#### D. 应用直接向 IP 发 UDP（无 DNS）

```text
preHandleMetadata：若非 fake-ip，DstIP 保留
可选 UDPSniff（QUIC 443 等）补全 Host
规则 → privateip / cnip（no-resolve）或 NETWORK,UDP / …
```

#### E. 系统 DNS（UDP/53）

被 TUN **`dns-hijack: any:53`** 劫持，进入 **内置 DNS 模块**，**不**按普通「访问 8.8.8.8:53 的 UDP 漏网」处理。这与访问远程 DNS 服务器的游戏/业务 UDP 不同。

### 6.8 UDP 排查清单

| 现象 | 可查方向 |
|------|----------|
| UDP 不通、TCP 正常 | 节点是否 `udp: false`；是否误进无 UDP 节点的 **🔀** 而非 **🔀 UDP** |
| 游戏 NAT 差 | `endpoint-independent-nat`、`udp-timeout`；是否应 DIRECT（`cn`） |
| 规则不符合预期 | 日志中 `NETWORK,UDP` 与 `MATCH` 谁命中；首包 UDPSniff 是否补到域名 |
| 解析慢 / 失败 | 本地 `nameserver`；`[UDP] 解析 IP 失败` 日志 |
| TCP/UDP 行为不一致 | 是否正常（见 6.2：`MATCH` vs `NETWORK,UDP`） |

### 6.9 UDP 常见误区

| 误区 | 说明 |
|------|------|
| UDP 和 TCP 一定进同一策略组 | 漏网时 UDP 走 **↩️ UDP**，TCP 走 **↩️**，规则位不同 |
| UDP 走代理也是节点解析域名 | 多数机场协议在本地 **ResolveUDP** 后传 **IP** |
| 配置了 `🔀` 就一定用其 UDP | 须命中带 `NETWORK,UDP` 的规则，或漏网 **↩️ UDP** |
| 所有 DNS 查询都走代理 | 53 端口被 hijack 到本地 DNS；fake-ip 仍不预解析目标 |

---

## 7. 分场景链路示例

以下「是否在 ruleset 内」为假设，实际以 ruleset 与日志为准。

### 7.1 国内站 `www.baidu.com`（假设在 `cn`，不在 fakeip-filter）


| 阶段  | 链路                                                                        |
| --- | ------------------------------------------------------------------------- |
| DNS | 若 fakeip-filter 未命中 → **fake-ip**；若命中 → **real-ip** + `direct-nameserver` |
| 规则  | `RULE-SET,cn` → **⬆️ DIRECT**                                             |
| 连接  | 本机解析（如需）→ 运营商出口直连                                                         |


### 7.2 国外站 `www.google.com`（假设未命中前述集，落 `MATCH`）


| 阶段     | 链路                                        |
| ------ | ----------------------------------------- |
| DNS    | **fake-ip**（198.18.x.x），查询阶段不解析真实 IP      |
| 规则     | `MATCH` → **↩️ FALLBACK** → 通常链到 **🔀** 等 |
| TCP 连接 | 还原域名 → 代理协议带域名 → **节点解析并访问**              |
| UDP 连接 | 见 **§6**：多走 `NETWORK,UDP` → **↩️ UDP** → 本地 ResolveUDP → 节点转发 **IP** |


### 7.3 自定义代理白名单 `api.example.com`（`wl-pxy`）


| 协议  | 策略组             |
| --- | --------------- |
| TCP | **🔀 AUTO**     |
| UDP | **🔀 AUTO UDP** |


### 7.4 AI `chat.openai.com`（`ai` 规则集）


| 协议  | 策略组             |
| --- | --------------- |
| TCP | **🚫 🇭🇰**     |
| UDP | **🚫 🇭🇰 UDP** |


### 7.5 局域网 `192.168.1.1` 或 `router.lan`


| 类型    | DNS                        | 规则                                  |
| ----- | -------------------------- | ----------------------------------- |
| 私网 IP | 无 / 或不关键                   | `privateip` + `no-resolve` → **⬆️** |
| 私网域名  | 常在 fakeip-filter → real-ip | `private` → **⬆️**                  |


### 7.6 国内 DNS IP `114.114.114.114:53`

无域名 → `cnip` + `no-resolve` → **⬆️ DIRECT**（不为规则做 DNS 查询）。

### 7.7 纯 IP 访问 `8.8.8.8:443`


| 步骤      | 行为                                       |
| ------- | ---------------------------------------- |
| Sniffer | 若 TLS 带 SNI，可还原域名再匹配规则                   |
| 无 SNI   | 按 IP 规则；通常非 cnip → `MATCH` → **↩️** → 代理 |
| DNS     | 应用未查域名则无 DNS 阶段                          |


### 7.8 漏网 UDP（简述）

详见 **§6 UDP 专题**；规则为 `NETWORK,UDP` → **↩️ UDP**。

---

## 8. Sniffer 的作用


| 配置                                         | 效果                         |
| ------------------------------------------ | -------------------------- |
| `parse-pure-ip: true`                      | 对直连 IP 的连接尝试嗅探             |
| HTTP/TLS/QUIC `override-destination: true` | 用嗅探到的 Host/SNI 覆盖目标，改善规则匹配 |
| `force-dns-mapping: true`                  | 强化 DNS 映射与嗅探协作             |


适用于：应用直接用 IP 连接、或 fake-ip 映射需与真实域名对齐的场景。

**UDP 补充**：新建 UDP NAT 会话时，首包可经 `UDPSniff`（配置里 QUIC 嗅探端口含 443）尝试从 QUIC 等载荷提取域名，再参与规则匹配；与 TCP 的 TLS/HTTP 嗅探并列。

---

## 9. 阶段对照表（自查用）

回答任一路径问题时，先分清三列：


| 问题      | DNS 查询阶段                   | TCP 连接阶段               | UDP 连接阶段                         |
| ------- | -------------------------- | ---------------------- | -------------------------------- |
| 目标是谁解析？ | fake-ip：不解析目标；real-ip：本地上游 | 代理：多为 **节点**；直连：**本地** | 代理：常 **本地 ResolveUDP**；直连：**本地** |
| 应用看到什么？ | 198.18.x.x 或真实 IP          | 连向假 IP 或真实 IP          | 同左                               |
| 规则在哪生效？ | 仅 `fake-ip-filter`         | `rules` 全文             | `rules` + `AND,(NETWORK,UDP,…)` / `NETWORK,UDP` |
| 漏网兜底       | —                          | `MATCH` → **↩️**          | `NETWORK,UDP` → **↩️ UDP**（在 `MATCH` 前）      |


---

## 10. 日志中建议关注的字段

在 Mihomo/Clash 调试日志中可核对：

- DNS：`fake-ip` / `real-ip`、返回的 IP
- 规则：命中的 `RULE-SET` 或规则类型
- 策略组：`🔀` / `⬆️` / `🇭🇰` 等
- 连接：metadata 中 `Host` 是否保留（代理 TCP 常为域名；UDP 代理多在 ResolveUDP 后仅有 `DstIP`）
- UDP：`[UDP] 解析 IP 失败`、`[UDP] 嗅探失败`、`NETWORK,UDP` 与 `MATCH` 谁命中

---

## 11. 常见误区


| 误区                            | 正确理解（本配置）                              |
| ----------------------------- | -------------------------------------- |
| fake-ip 会用 nameserver 预解析目标站  | **不会**；只分配假 IP                         |
| 走代理时一定是本地解析再给节点 IP            | **TCP 代理**多为传 **域名**，**节点解析**          |
| DNS 规则与路由规则是一回事               | **两套规则**，阶段不同                          |
| 配置了 nameserver 就等于所有流量用它解析目标  | nameserver 管多种场景，**不等于** fake-ip 目标预解析 |
| `redir-host` 与 `fake-ip` 行为相同 | **不同**；本文档针对 **fake-ip**               |
| UDP 与 TCP 漏网规则相同              | UDP 用 **`NETWORK,UDP`**，不用 `MATCH`          |
| UDP 代理与 TCP 代理解析方式相同        | UDP 多在 **本地 ResolveUDP**，TCP 多 **节点解析域名** |


---

## 12. 参考：内核行为依据（便于二次核对）


| 行为                     | 代码位置（mihomo / 本仓库 core）                                                                |
| ---------------------- | -------------------------------------------------------------------------------------- |
| fake-ip 只映射、不 Exchange | `dns/middleware.go` → `withFakeIP` → `fakePool.Lookup`                                 |
| 假 IP 还原域名              | `tunnel/tunnel.go` → `preHandleMetadata`                                               |
| 代理传域名                  | `constant/metadata.go` → `AddrType`；`adapter/outbound/util.go` → `serializesSocksAddr` |
| UDP 本地解析               | `adapter/outbound/base.go` → `ResolveUDP`；`tunnel/connection.go` → `processPacket`      |
| UDP 会话与嗅探             | `tunnel/tunnel.go` → `handleUDPConn`；`component/sniffer/dispatcher.go` → `UDPSniff`   |
| 直连 UDP                 | `adapter/outbound/direct.go` → `ListenPacketContext` + `DirectHostResolver`              |
| 直连 DNS（TCP）            | `adapter/outbound/direct.go` + `direct-nameserver`                                     |


官方文档：[DNS 配置](https://wiki.metacubex.one/config/dns/) · [fake-ip-filter rule 模式](https://wiki.metacubex.one/config/dns/#fake-ip-filter-mode)

---

*文档版本：含 UDP 专题扩展；若 yaml/脚本变更请同步更新本文档。*