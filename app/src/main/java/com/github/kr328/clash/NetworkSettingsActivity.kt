package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.net.Inet4Address
import java.net.NetworkInterface
import java.io.File

class NetworkSettingsActivity : BaseActivity<NetworkSettingsDesign>() {
    override suspend fun main() {
        val serviceStore = ServiceStore(this)
        val persistOverride = withClash { queryOverride(Clash.OverrideSlot.Persist) }
        val sessionOverride = withClash { queryOverride(Clash.OverrideSlot.Session) }
        val design = NetworkSettingsDesign(
            this,
            uiStore,
            serviceStore,
            clashRunning,
            persistOverride.allowLan ?: false,
            resolveLanAddresses(serviceStore, sessionOverride, persistOverride),
        )

        defer {
            withClash {
                patchOverride(Clash.OverrideSlot.Persist, persistOverride)
            }
        }

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        NetworkSettingsDesign.Request.StartAccessControlList ->
                            startActivity(AccessControlActivity::class.intent)
                        NetworkSettingsDesign.Request.StartLanDevices ->
                            startActivity(ConnectionsActivity::class.intent)
                        is NetworkSettingsDesign.Request.UpdateAllowLan -> {
                            val turningOff = persistOverride.allowLan == true && !it.enabled
                            persistOverride.allowLan = it.enabled
                            if (turningOff) {
                                withClash { closeLanConnections() }
                            }
                        }
                        is NetworkSettingsDesign.Request.CopyLanAddress ->
                            copyTextToClipboard(it.address)
                    }
                }
            }
        }
    }

    private fun copyTextToClipboard(text: String) {
        getSystemService<ClipboardManager>()?.setPrimaryClip(
            ClipData.newPlainText("lan_address", text)
        )
    }

    private fun resolveLanAddresses(
        serviceStore: ServiceStore,
        sessionOverride: ConfigurationOverride,
        persistOverride: ConfigurationOverride,
    ): List<String> {
        val port = resolveLanPort(serviceStore, sessionOverride, persistOverride)
        val addresses = mutableListOf<String>()
        val networks = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (networks.hasMoreElements()) {
            val network = networks.nextElement()
            if (!network.isUp || network.isLoopback || network.isVirtual) continue

            val inetAddresses = network.inetAddresses
            while (inetAddresses.hasMoreElements()) {
                val address = inetAddresses.nextElement()
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                addresses.add("${address.hostAddress}:$port")
            }
        }

        // 与电脑端一致：优先展示典型家宽网段，避免仅因字符串排序把 10.x 放在 192.168.x 之前
        return addresses.distinct().sortedWith { a, b ->
            val pa = lanAddressSortKey(a)
            val pb = lanAddressSortKey(b)
            if (pa != pb) pa.compareTo(pb) else a.compareTo(b)
        }
    }

    /** 192.168 / 10 / 172 私网中，优先 192.168 作为“局域网”主地址（多网卡时常有 10.x 与 192.168.x 并存）。 */
    private fun lanAddressSortKey(addressWithPort: String): Int {
        val ip = addressWithPort.substringBefore(':')
        return when {
            ip.startsWith("192.168.") -> 0
            ip.startsWith("10.") -> 1
            ip.startsWith("172.") -> 2
            else -> 3
        }
    }

    private fun resolveLanPort(
        serviceStore: ServiceStore,
        sessionOverride: ConfigurationOverride,
        persistOverride: ConfigurationOverride,
    ): Int {
        val profileMixedPort = resolveProfileMixedPort(serviceStore)
        return sessionOverride.mixedPort
            ?: persistOverride.mixedPort
            ?: sessionOverride.httpPort
            ?: persistOverride.httpPort
            ?: sessionOverride.socksPort
            ?: persistOverride.socksPort
            ?: profileMixedPort
            ?: 10801
    }

    private fun resolveProfileMixedPort(serviceStore: ServiceStore): Int? {
        val activeProfile = serviceStore.activeProfile ?: return null
        val config = File(filesDir, "imported/$activeProfile/config.yaml")
        if (!config.exists()) return null

        val mixedPortRegex = Regex("""^\s*mixed-port\s*:\s*(\d+)\s*(?:#.*)?$""")
        return config.useLines { lines ->
            lines.firstNotNullOfOrNull { line ->
                mixedPortRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }
    }
}
