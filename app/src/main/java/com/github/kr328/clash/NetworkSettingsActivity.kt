package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
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
                            if (it.enabled) {
                                // 每次开启局域网时自动刷新 bind-address，避免网络切换后仍绑定旧 IP。
                                resolvePreferredIpv4Address()?.let { ip ->
                                    persistOverride.bindAddress = ip
                                }
                            }
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
        val preferredIpv4 = resolvePreferredIpv4Address()
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

        // 优先展示系统当前活跃网络对应的 IPv4，降低多网卡/热点/VPN 场景下选错地址的概率。
        return addresses.distinct().sortedWith { a, b ->
            val pa = lanAddressSortKey(a, preferredIpv4)
            val pb = lanAddressSortKey(b, preferredIpv4)
            if (pa != pb) pa.compareTo(pb) else a.compareTo(b)
        }
    }

    private fun resolvePreferredIpv4Address(): String? {
        val connectivityManager = getSystemService<ConnectivityManager>() ?: return null
        val activeNetwork = connectivityManager.activeNetwork

        if (activeNetwork != null) {
            val activeCaps = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                connectivityManager.getLinkProperties(activeNetwork)?.let { props ->
                    extractIpv4Address(props)?.let { return it }
                }
            }
        }

        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

            connectivityManager.getLinkProperties(network)?.let { props ->
                extractIpv4Address(props)?.let { return it }
            }
        }

        if (activeNetwork != null) {
            connectivityManager.getLinkProperties(activeNetwork)?.let { props ->
                extractIpv4Address(props)?.let { return it }
            }
        }

        return null
    }

    private fun extractIpv4Address(linkProperties: LinkProperties): String? {
        return linkProperties.linkAddresses
            .asSequence()
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }

    /** 优先活跃网络地址，其次按常见私网网段排序。 */
    private fun lanAddressSortKey(addressWithPort: String, preferredIpv4: String?): Int {
        val ip = addressWithPort.substringBefore(':')
        if (!preferredIpv4.isNullOrBlank() && ip == preferredIpv4) return -1

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
