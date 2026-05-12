package com.github.kr328.clash.service.config

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.LogMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val liteJson = Json { encodeDefaults = false }

/** 对齐 Hub PATCH /configs 的子集字段，仅写入非 null（encodeDefaults=false）。 */
@Serializable
private data class ConnectivityLitePayload(
    @SerialName("allow-lan") val allowLan: Boolean? = null,
    @SerialName("bind-address") val bindAddress: String? = null,
)

internal object OverrideRuntimeApplier {
    private const val TAG = "OverrideRuntime"

    /**
     * 在 [Clash.load] 全量重载成功后调用。
     *
     * 与桌面端 `generate` 后从上一版运行时合并 `allow-lan` 等问题同类：native 导出的运行 YAML 可能仍按订阅默认序列化，
     * 而核心监听状态已由 load 前写入的 Session 合并；对 JNI 已支持的字段再 PATCH 一次，使 Hub 状态与 Persist 一致，
     * 避免「界面/核心已开局域网，运行配置里仍是 false」。
     */
    fun applyPersistRuntimeLightFieldsAfterFullLoad() {
        val persist = Clash.queryOverride(Clash.OverrideSlot.Persist)
        persist.logLevel?.let { level ->
            if (!Clash.patchRuntimeLogLevel(level.toClashYamlKey())) {
                Log.w("$TAG: 轻量 PATCH log-level（reload 后对齐）失败")
            }
        }
        val bindNext = persist.bindAddress?.trim().orEmpty()
        val hasExplicitAllowLan = persist.allowLan != null
        val bindPush = bindNext.isNotEmpty()
        if (!hasExplicitAllowLan && !bindPush) return
        val json = liteJson.encodeToString(
            ConnectivityLitePayload.serializer(),
            ConnectivityLitePayload(
                allowLan = persist.allowLan.takeIf { hasExplicitAllowLan },
                bindAddress = bindNext.takeIf { bindPush },
            ),
        )
        if (!Clash.patchConnectivityJsonSubset(json)) {
            Log.w("$TAG: 轻量 PATCH allow-lan/bind-address（reload 后对齐）失败")
        }
    }

    /** 在 native writeOverride 成功后调用，使轻量档位与持久化 overlay 一致。 */
    fun apply(previous: ConfigurationOverride, next: ConfigurationOverride) {
        if (previous.logLevel != next.logLevel && next.logLevel != null) {
            Clash.patchRuntimeLogLevel(next.logLevel!!.toClashYamlKey())
        }

        val allowPush = previous.allowLan != next.allowLan && next.allowLan != null
        val bindNext = next.bindAddress?.trim().orEmpty()
        val bindPush =
            normalizeBind(previous.bindAddress) != normalizeBind(next.bindAddress) && bindNext.isNotEmpty()

        if (!allowPush && !bindPush) return

        val json = liteJson.encodeToString(
            ConnectivityLitePayload.serializer(),
            ConnectivityLitePayload(
                allowLan = if (allowPush) next.allowLan else null,
                bindAddress = bindNext.takeIf { bindPush },
            ),
        )

        if (!Clash.patchConnectivityJsonSubset(json)) {
            Log.w("$TAG: 轻量 PATCH allow-lan/bind-address 运行时应用失败")
        }
    }

    private fun normalizeBind(raw: String?) = raw?.trim().orEmpty()
}

internal fun LogMessage.Level.toClashYamlKey(): String = when (this) {
    LogMessage.Level.Debug -> "debug"
    LogMessage.Level.Info -> "info"
    LogMessage.Level.Warning -> "warning"
    LogMessage.Level.Error -> "error"
    LogMessage.Level.Silent -> "silent"
    LogMessage.Level.Unknown -> "info"
}
