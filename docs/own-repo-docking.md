# 自有仓库对接基线（断上游浮动�?
生效日期�?026-08-06  
策略：从当前钉扎版本起，内核与桌面特权服务只使用 MikeKen-Ken 仓库；不再跟�?MetaCubeX / 上游 clash-verge-rev service 浮动 tip�?
## 钉扎�?
| 组件 | 仓库 | 指针 / 版本 | 接入方式 |
|------|------|-------------|----------|
| Android 主仓 | MikeKen-Ken/ClashMetaForAndroid | 本仓 | �?|
| Mihomo 内核 | MikeKen-Ken/mihomo | `1b98f612 / ���� `alpha-1b98f61`` | submodule `core/src/foss/golang/clash` |
| 桌面客户�?| MikeKen-Ken/clash-verge-rev | 父仓 gitlink | submodule |
| 桌面特权服务 | MikeKen-Ken/clash-verge-service-ipc | `pin/v2.1.2` = `dc7238ef` | path 依赖 + prebuild 本地 cargo 编译 |

## 已落地改�?
1. CI：去�?`git submodule update --remote`，只 checkout 父仓 gitlink�?2. CMake：按 `core/src/foss/golang/clash` 路径 `rev-parse`，不再盲�?foreach �?2 �?hash�?3. 桌面 prebuild：`scripts/mihomo.pin.json` 钉死 sidecar 版本，不再读浮动 `version.txt`�?4. 桌面服务：继续仅�?`vendor/clash-verge-service-ipc` 编译，不下载上游 service release�?
## 升级内核流程

1. �?MikeKen-Ken/mihomo 合入并发布对�?`Prerelease-Alpha` 资产（含 `version.txt` 与平台包）�?2. 父仓：`git submodule update --remote` **不要**用于日常 CI；改为手动把 gitlink 推到目标 commit 后提交�?3. 同步�?`clash-verge-rev/scripts/mihomo.pin.json` �?`commit` + `version`�?4. 提交父仓�?`clash-verge-rev` �?submodule 指针更新�?
## 仍可能引用外部资源（非服�?内核协议�?
- GeoIP / Geosite / MMDB 默认仍可能指�?MetaCubeX meta-rules-dat（规则数据，非特权服务）�?- Android Maven backup、部分文�?wiki 链接�?- 若要一并迁到自有镜像，另开任务�?
## 验证清单

- [ ] `git submodule status` �?mihomo �?` 8c805465…`（无前缀 `-`�?- [ ] 本地/CI 构建不再依赖 `--remote`
- [ ] 桌面 `node scripts/prebuild.mjs` 日志�?`mihomo pinned: alpha-1b98f61`
- [ ] 桌面「安装服务」仍来自本地 service-ipc 编译产物

## 后续修复（优先服务端�?026-08-06�?
已按「尽量改 service/core，少�?UI」补齐原排查清单 #5�?12�?15�?
- #5/#6 Manifest �?${applicationId} / REQUEST_UPDATE（声明侧�?- #7/#8 native global ref 释放�?logcat unsubscribe
- #9 health-check JSON 事件 + service 兼容旧前缀
- #10 Proxy 类型名序列化 + schemaVersion + ordinal 钳制
- #11 Remote 绑定超时与有限重试（唯一必要�?app 逻辑改动�?- #12 StatusProvider 运行态与停服清空 profile
- #15 Linux amd64/arm64 映射 + mihomo CI linux job
