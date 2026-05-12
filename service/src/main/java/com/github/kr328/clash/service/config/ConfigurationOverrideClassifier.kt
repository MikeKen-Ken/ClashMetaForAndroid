package com.github.kr328.clash.service.config

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val comparatorJson = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
}

/**
 * **RuntimeLightOnly**：仅桌面端视为「运行时轻量 PATCH」的子集字段变化（须有对应 JNI）。
 * **FullConfigurationReload**：其它覆写仍以 Clash.load 合并 profile + override。
 *
 * Persist / Session **同一逻辑**区分；避免「误以为 Session 可以随意免 load」而与合并语义不一致。
 */
internal enum class OverrideReloadDecision {
    RuntimeLightOnly,
    FullConfigurationReload,
}

internal object ConfigurationOverrideClassifier {

    fun classify(
        @Suppress("UNUSED_PARAMETER") slot: Clash.OverrideSlot,
        previous: ConfigurationOverride,
        next: ConfigurationOverride,
    ): OverrideReloadDecision {
        if (encodedEq(ConfigurationOverride.serializer(), previous, next)) {
            return OverrideReloadDecision.RuntimeLightOnly
        }

        val kinds = Diff.collectKinds(previous, next)

        val onlyWhitelist = kinds.isNotEmpty() && kinds.all { it in LIGHT_RUNTIME_KINDS }

        return if (onlyWhitelist) {
            OverrideReloadDecision.RuntimeLightOnly
        } else {
            OverrideReloadDecision.FullConfigurationReload
        }
    }

    private enum class Kind {
        LOG_LEVEL,
        ALLOW_LAN,
        BIND_ADDRESS,
        /** JNI 暂不覆盖的回退语义：必须整条 load（与 whitelist 不交） */
        LEGACY_REQUIRES_LOAD,
        PORT_FAMILY,
        AUTH,
        EXTERNAL_CONTROLLER_FAMILY,
        SECRET,
        EXTERNAL_CONTROLLER_CORS,
        MODE,
        IPV6,
        HOSTS,
        DNS,
        UNIFIED_DELAY,
        GEODATA_MODE,
        TCP_CONCURRENT,
        FIND_PROCESS_MODE,
        SNIFFER,
        GEOX_URL,
        APP_CFA_ANDROID,
        TUN_FAMILY,
        PROXY_SELECTIONS,
        PROXY_ADS_BLOCK,
        PROXY_DELAY_TEST,
    }

    private val LIGHT_RUNTIME_KINDS: Set<Kind> =
        setOf(Kind.LOG_LEVEL, Kind.ALLOW_LAN, Kind.BIND_ADDRESS)

    private object Diff {
        fun collectKinds(
            prev: ConfigurationOverride,
            next: ConfigurationOverride,
        ): MutableSet<Kind> {
            val kinds = mutableSetOf<Kind>()

            fun addIf(cond: Boolean, kind: Kind) {
                if (cond) kinds += kind
            }

            addIf(prev.httpPort != next.httpPort, Kind.PORT_FAMILY)
            addIf(prev.socksPort != next.socksPort, Kind.PORT_FAMILY)
            addIf(prev.redirectPort != next.redirectPort, Kind.PORT_FAMILY)
            addIf(prev.tproxyPort != next.tproxyPort, Kind.PORT_FAMILY)
            addIf(prev.mixedPort != next.mixedPort, Kind.PORT_FAMILY)

            addIf(prev.authentication != next.authentication, Kind.AUTH)

            addIf(prev.externalController != next.externalController, Kind.EXTERNAL_CONTROLLER_FAMILY)
            addIf(prev.externalControllerTLS != next.externalControllerTLS, Kind.EXTERNAL_CONTROLLER_FAMILY)
            addIf(prev.secret != next.secret, Kind.SECRET)

            addIf(
                !encodedEq(ConfigurationOverride.ExternalControllerCors.serializer(), prev.externalControllerCors, next.externalControllerCors),
                Kind.EXTERNAL_CONTROLLER_CORS,
            )

            kinds.collectAllowLanDelta(prev.allowLan, next.allowLan)
            kinds.collectBindDelta(prev.bindAddress, next.bindAddress)

            addIf(prev.mode != next.mode, Kind.MODE)
            addIf(prev.logLevel != next.logLevel, Kind.LOG_LEVEL)
            addIf(prev.ipv6 != next.ipv6, Kind.IPV6)

            addIf(prev.hosts != next.hosts, Kind.HOSTS)

            addIf(!encodedEq(ConfigurationOverride.Dns.serializer(), prev.dns, next.dns), Kind.DNS)

            addIf(prev.unifiedDelay != next.unifiedDelay, Kind.UNIFIED_DELAY)
            addIf(prev.geodataMode != next.geodataMode, Kind.GEODATA_MODE)
            addIf(prev.tcpConcurrent != next.tcpConcurrent, Kind.TCP_CONCURRENT)
            addIf(prev.findProcessMode != next.findProcessMode, Kind.FIND_PROCESS_MODE)

            addIf(!encodedEq(ConfigurationOverride.Sniffer.serializer(), prev.sniffer, next.sniffer), Kind.SNIFFER)

            addIf(!encodedEq(ConfigurationOverride.GeoXUrl.serializer(), prev.geoxurl, next.geoxurl), Kind.GEOX_URL)

            addIf(!encodedEq(ConfigurationOverride.App.serializer(), prev.app, next.app), Kind.APP_CFA_ANDROID)

            addIf(!encodedEq(ConfigurationOverride.Tun.serializer(), prev.tun, next.tun), Kind.TUN_FAMILY)

            addIf(prev.proxySelections != next.proxySelections, Kind.PROXY_SELECTIONS)
            addIf(prev.proxyAdsBlock != next.proxyAdsBlock, Kind.PROXY_ADS_BLOCK)

            addIf(prev.proxyDelayTestTimeoutMs != next.proxyDelayTestTimeoutMs, Kind.PROXY_DELAY_TEST)
            addIf(prev.proxyDelayTestConcurrency != next.proxyDelayTestConcurrency, Kind.PROXY_DELAY_TEST)

            return kinds
        }

        /** allow-lan 从显式布尔回落为覆写默认值（Kotlin null）时需整包 reload。 */
        private fun MutableSet<Kind>.collectAllowLanDelta(prev: Boolean?, next: Boolean?) {
            if (prev == next) return
            if (next == null && prev != null) {
                this += Kind.LEGACY_REQUIRES_LOAD
                return
            }
            if (next != null) {
                this += Kind.ALLOW_LAN
            }
        }

        /** bind-address 清空为非空时需整包 reload 才能对齐 profile。 */
        private fun MutableSet<Kind>.collectBindDelta(prev: String?, next: String?) {
            val p = prev?.trim().orEmpty()
            val n = next?.trim().orEmpty()
            if (p == n) return
            if (n.isEmpty() && p.isNotEmpty()) {
                this += Kind.LEGACY_REQUIRES_LOAD
                return
            }
            if (n.isNotEmpty()) {
                this += Kind.BIND_ADDRESS
            }
        }
    }
}


private fun <T> encodedEq(serializer: KSerializer<T>, a: T, b: T): Boolean {
    return comparatorJson.encodeToString(serializer, a) == comparatorJson.encodeToString(serializer, b)
}
