## Clash Meta for Android (MikeKen-Ken)

基于 Clash.Meta 的 Android GUI；自本 fork 当前版本起，**内核与桌面特权服务只对接自有仓库**，不再跟随 MetaCubeX / clash-verge-rev 上游浮动版本。

### 自有仓库对接基线

| 组件 | 仓库 | 当前钉扎 |
|------|------|----------|
| Android 应用 | [MikeKen-Ken/ClashMetaForAndroid](https://github.com/MikeKen-Ken/ClashMetaForAndroid) | 本仓 |
| 内核 Mihomo | [MikeKen-Ken/mihomo](https://github.com/MikeKen-Ken/mihomo) | `a615b8e5` / `alpha-a615b8e` |
| 桌面客户端 | [MikeKen-Ken/clash-verge-rev](https://github.com/MikeKen-Ken/clash-verge-rev) | submodule |
| 桌面特权服务 | [MikeKen-Ken/clash-verge-service-ipc](https://github.com/MikeKen-Ken/clash-verge-service-ipc) | `pin/v2.1.2` (`dc7238ef`) |

升级内核时须同步：父仓 gitlink + `clash-verge-rev/scripts/mihomo.pin.json`。详见 `docs/own-repo-docking.md`。

### Feature

Feature of Clash.Meta / Mihomo（本 fork 内核）。

### Requirement

- Android 5.0+ (minimum)
- Android 7.0+ (recommend)
- `armeabi-v7a` , `arm64-v8a`, `x86` or `x86_64` Architecture

### Build

1. Update submodules（**不要**加 `--remote`，必须用父仓钉死的指针）

   ```bash
   git submodule update --init --recursive
   ```

2. Install **OpenJDK 21**, **Android SDK**, **CMake** and **Golang**

3. Create `local.properties` in project root with

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

4. (Optional) Custom app package name. Add the following configuration to `local.properties`.

   ```properties
   # config your own applicationId, or it will be 'com.github.metacubex.clash'
   custom.application.id=com.my.compile.clash
   # remove application id suffix, or the application id will be 'com.github.metacubex.clash.alpha'
   remove.suffix=true
   ```

5. Create `signing.properties` in project root with

   ```properties
   keystore.path=/path/to/keystore/file
   keystore.password=<key store password>
   key.alias=<key alias>
   key.password=<key password>
   ```

6. Build

   ```bash
   ./gradlew app:assembleAlphaRelease
   ```

### Automation

APP package name defaults to `com.github.metacubex.clash.meta`（可按 `local.properties` 覆盖）。Intent action 须与实际 `applicationId` 一致。

- Toggle Clash.Meta service status
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `<applicationId>.action.TOGGLE_CLASH`
- Start Clash.Meta service
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `<applicationId>.action.START_CLASH`
- Stop Clash.Meta service
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `<applicationId>.action.STOP_CLASH`
- Import a profile
  - URL Scheme `clash://install-config?url=<encoded URI>` or `clashmeta://install-config?url=<encoded URI>`

### Contribution and Project Maintenance

#### Meta Kernel

- 本仓内核来自 **MikeKen-Ken/mihomo**（submodule `core/src/foss/golang/clash`），不再使用 MetaCubeX `android-real` 自动同步。
- 内核改动请提交到自有 `mihomo` 仓库，再更新本仓 gitlink 与桌面 `mihomo.pin.json`。

#### Maintenance

- CI `Build Pre-Release` 使用父仓 gitlink 初始化 submodule（已去掉 `--remote`）。
- 桌面特权服务仅从 `vendor/clash-verge-service-ipc` 本地编译，不下载上游 service release。
- 桌面 mihomo sidecar 版本以 `clash-verge-rev/scripts/mihomo.pin.json` 钉死。
