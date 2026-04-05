package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
        to = { it?.toString() ?: "" }
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        defaultValue = true
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = false
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "mixed"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )

    /**
     * 与 VPN 同进程持久化，供配置加载前恢复 Session 的 mode（主进程 UiStore 在 :background 下不可靠）。
     * 空串表示尚未写入，由 ConfigurationModule 从主进程 ui 偏好迁移一次。
     */
    var persistedProxyUiMode: String by store.string(
        key = "persisted_proxy_ui_mode",
        defaultValue = "",
    )

    fun persistedProxyModeOrDefault(): TunnelState.Mode {
        if (persistedProxyUiMode.isEmpty()) return TunnelState.Mode.Rule
        return TunnelState.Mode.values().find { it.name == persistedProxyUiMode } ?: TunnelState.Mode.Rule
    }
}