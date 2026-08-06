# 本机项目与服务版本不兼容排查（仅列问题，不改业务代码）

排查日期：2026-08-06
范围：Android 主仓 + 内嵌 Mihomo submodule + clash-verge-rev 桌面客户端/特权服务
约束：未初始化 submodule、未拉依赖、未改构建/业务代码。

## 版本事实（核实后）

| 组件 | 卡片原值 | 核实结果 |
|------|----------|----------|
| Android 应用 | 2.11.22 (211022) | 正确（build.gradle.kts） |
| go.mod 名义 Mihomo | v1.7.0 + replace 本地 | 正确（core/src/main/golang/go.mod） |
| Mihomo submodule 指针 | 8c805465… 未初始化 | 正确：git submodule status 为 -8c805465…；目录存在但 0 文件、无 .git |
| 桌面客户端 package | 2.4.6 | 正确（clash-verge-rev/package.json） |
| 桌面 git describe | v2.4.5-383 | 正确：v2.4.5-383-g0378e0e6（父仓 submodule 摘要另显示 v2.4.2-1284，以子仓 git describe 为准） |
| 桌面服务 IPC | 2.1.2 / v2.0.26-46 / 2026-01 | 正确：Cargo 2.1.2，v2.0.26-46-gdc7238e，提交日 2026-01-26 |
| 根仓 describe | — | Prerelease-alpha-6-g3d1bcefb |

三层「服务」划分属实：

1. Android Binder / VpnService：service/
2. 内嵌 Mihomo 核心：core/ + core/src/foss/golang/clash
3. 桌面特权服务：clash-verge-rev/vendor/clash-verge-service-ipc

## Checklist 核实

### 1. Mihomo submodule 未初始化，Android core 无法构建 — confirmed

- git submodule status：`-8c805465bf188fc5013b913714363474ed16d937 core/src/foss/golang/clash`
- core/src/foss/golang/clash 文件数 = 0，无 .git
- go.mod：replace github.com/metacubex/mihomo => ../../foss/golang/clash

### 2. CMake core 版本 hash 提取可能错误 — confirmed

- 文件：core/src/main/cpp/CMakeLists.txt（约 L7–L16）
- 使用 git submodule foreach + list(GET COMMIT_HASH 1)，依赖输出顺序，工作目录在 core/src/main/cpp
- 本机复现（mihomo 未初始化时）：foreach 输出 clash-verge-rev / custome-rule 的 hash；GET index 1 会取到 verge 的 0378e0e6…，而非 mihomo

### 3. CI --remote 导致本地/CI 核心版本漂移 — confirmed

- .github/workflows/build-pre-release.yaml L36：git submodule update --init --recursive --remote --force
- 会跟踪 .gitmodules 的 branch = Alpha，与提交指针 8c805465 脱钩

### 4. README 与实际内核/构建配置不一致 — confirmed

- README 写 OpenJDK 11；CI 为 Java 21
- README 称内核来自 MetaCubeX/Clash.Meta 的 android-real；.gitmodules 实际为 MikeKen-Ken/mihomo、branch = Alpha
- README 外部控制 action 写死 .meta；同时文档又允许 custom.application.id / remove.suffix

### 5. 外部控制 action 硬编码 .meta，与动态包名冲突 — confirmed

- 代码：Intents.ACTION_* 使用运行时 packageName（common/.../Intents.kt）
- Manifest：app/src/main/AndroidManifest.xml L89–97 硬编码 com.github.metacubex.clash.meta.action.*
- 默认 Meta flavor（applicationIdSuffix = .meta）可对上；Alpha / 自定义 applicationId 会错位

### 6. Profile REQUEST_UPDATE vs PROFILE_REQUEST_UPDATE 不一致 — confirmed

双重问题：

1. 常量：ACTION_PROFILE_REQUEST_UPDATE = "$packageName.intent.action.REQUEST_UPDATE"（Intents.kt L22）
2. Manifest：{applicationId}.intent.action.PROFILE_REQUEST_UPDATE（service/.../AndroidManifest.xml L84）— 缺 $，且 action 后缀不同

### 7. Health-check JNI global ref 疑似泄漏 — confirmed

- main.c nativeSubscribeHealthCheck：NewGlobalRef 后无对称 DeleteGlobalRef
- log.go subscribeHealthCheck：覆盖 healthCheckCallback 时不释放旧指针
- 对比 logcat：关闭路径会 release_object → DeleteGlobalRef

### 8. Log 观察者取消后 native 订阅可能残留 — confirmed

- Clash.subscribeLogcat()：Channel.cancel() 后 JNI received 仍 trySend（通常不抛异常）
- Native 仅在 logcat_received 返回非 0（JNI 异常）时 UnSubscribe + release_object
- ClashManager.setLogObserver(null) 只 cancel() Channel + forceGc()，无显式 unsubscribe API

### 9. Health-check 私有刷新协议脆弱 — confirmed（Android 侧）

- HealthCheckNotificationModule 把 groupName 复用为多路协议：proxy-group-refresh TAB、max-connect-times TAB
- 无版本字段；与真实组名冲突/演进时易碎
- 生产端在未初始化的 Mihomo 子模块内，本机未核对源码

### 10. Proxy 模型无协议版本协商 — confirmed

- core/.../model/Proxy.kt：Parcelable + kotlinx.serialization，无 schema/version 字段
- Type 枚举随内核扩展；跨进程/跨版本反序列化无协商

### 11. Binder 无限重试可能挂起 UI — confirmed

- app/.../util/Remote.kt：withClash / withProfile 对 DeadObjectException while (true) 重试
- Resource.get()：服务未就绪时挂起等待；无超时

### 12. 服务状态用 profile 推断不准确 — confirmed

- Broadcasts.register / TileService：clashRunning = currentProfile() != null
- StatusProvider：仅在 serviceRunning 时返回 Bundle，但 name 可为 null → 服务已跑、UI 仍显示未运行

### 13. 桌面服务钉死 v2.1.2，与上游 v2.6.x 不兼容 — confirmed

- clash-verge-rev/src-tauri/Cargo.toml：path 依赖 2.1.2，注释明确升到上游 v2.6.x 需重写

### 14. 桌面/Android Mihomo 版本未对齐 — partial

- 同源 fork：MikeKen-Ken/mihomo
- Android：git 指针 8c805465（本地无源码，无法读实际版本标签）
- 桌面：prebuild.mjs 拉取 Prerelease-Alpha 的 version.txt（浮动最新），且 META_CUSTOM_ASSET_MAP 仅 win/darwin
- 结论：无共享钉扎；结构性漂移属实；精确 commit/tag 差值待 submodule 初始化与一次 pnpm prebuild 后才能比

### 15. 桌面 Linux 打包资产不全 — confirmed

- src-tauri/resources、src-tauri/sidecar 被 gitignore，本机均不存在（需 prebuild）
- packages/linux/ 模板存在（desktop / post-install / pre-remove）
- 关键缺口：scripts/prebuild.mjs 的 META_CUSTOM_ASSET_MAP 仅 win32-x64、darwin-arm64，Linux 直接抛 mihomo custom unsupported platform
- tauri.linux.conf.json 仍声明 externalBin 需要 service + verge-mihomo-custom

## 风险摘要

1. 阻塞构建：Android Mihomo 未初始化是当前最严重阻塞。
2. 版本漂移：CI --remote + 桌面浮动 Prerelease-Alpha vs Android 固定指针。
3. 运行时正确性：硬编码 action、Profile update intent、服务状态推断、Binder 无限等待。
4. Native 生命周期：health-check / logcat GlobalRef 与订阅残留。
5. 桌面 Linux：即便跑 prebuild，当前 asset map 也不支持 Linux mihomo sidecar。

## 未决

- Android 指针 8c805465 对应的 Mihomo 标签/版本号（需 init submodule，本卡禁止）
- 桌面当前 META_CUSTOM_VERSION 缓存值（需网络/pnpm prebuild，本卡禁止）
- proxy-group-refresh 等前缀的内核生产端实现细节（源码不在工作树）