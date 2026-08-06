# 自有仓库对接基线（断上游浮动）

生效日期：2026-08-06  
策略：从当前钉扎版本起，内核与桌面特权服务只使用 MikeKen-Ken 仓库；不再跟随 MetaCubeX / 上游 clash-verge-rev service 浮动 tip。

## 钉扎表

| 组件 | 仓库 | 指针 / 版本 | 接入方式 |
|------|------|-------------|----------|
| Android 主仓 | MikeKen-Ken/ClashMetaForAndroid | 本仓 | — |
| Mihomo 内核 | MikeKen-Ken/mihomo | `8c805465` / 产物 `alpha-8c80546` | submodule `core/src/foss/golang/clash` |
| 桌面客户端 | MikeKen-Ken/clash-verge-rev | 父仓 gitlink | submodule |
| 桌面特权服务 | MikeKen-Ken/clash-verge-service-ipc | `pin/v2.1.2` = `dc7238ef` | path 依赖 + prebuild 本地 cargo 编译 |

## 已落地改动

1. CI：去掉 `git submodule update --remote`，只 checkout 父仓 gitlink。
2. CMake：按 `core/src/foss/golang/clash` 路径 `rev-parse`，不再盲取 foreach 第 2 个 hash。
3. 桌面 prebuild：`scripts/mihomo.pin.json` 钉死 sidecar 版本，不再读浮动 `version.txt`。
4. 桌面服务：继续仅从 `vendor/clash-verge-service-ipc` 编译，不下载上游 service release。

## 升级内核流程

1. 在 MikeKen-Ken/mihomo 合入并发布对应 `Prerelease-Alpha` 资产（含 `version.txt` 与平台包）。
2. 父仓：`git submodule update --remote` **不要**用于日常 CI；改为手动把 gitlink 推到目标 commit 后提交。
3. 同步改 `clash-verge-rev/scripts/mihomo.pin.json` 的 `commit` + `version`。
4. 提交父仓对 `clash-verge-rev` 的 submodule 指针更新。

## 仍可能引用外部资源（非服务/内核协议）

- GeoIP / Geosite / MMDB 默认仍可能指向 MetaCubeX meta-rules-dat（规则数据，非特权服务）。
- Android Maven backup、部分文档 wiki 链接。
- 若要一并迁到自有镜像，另开任务。

## 验证清单

- [ ] `git submodule status` 中 mihomo 为 ` 8c805465…`（无前缀 `-`）
- [ ] 本地/CI 构建不再依赖 `--remote`
- [ ] 桌面 `node scripts/prebuild.mjs` 日志含 `mihomo pinned: alpha-8c80546`
- [ ] 桌面「安装服务」仍来自本地 service-ipc 编译产物

## 后续修复（优先服务端，2026-08-06）

已按「尽量改 service/core，少动 UI」补齐原排查清单 #5–#12、#15：

- #5/#6 Manifest 用 ${applicationId} / REQUEST_UPDATE（声明侧）
- #7/#8 native global ref 释放与 logcat unsubscribe
- #9 health-check JSON 事件 + service 兼容旧前缀
- #10 Proxy 类型名序列化 + schemaVersion + ordinal 钳制
- #11 Remote 绑定超时与有限重试（唯一必要的 app 逻辑改动）
- #12 StatusProvider 运行态与停服清空 profile
- #15 Linux amd64/arm64 映射 + mihomo CI linux job
