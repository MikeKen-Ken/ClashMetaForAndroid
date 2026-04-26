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

class NetworkSettingsActivity : BaseActivity<NetworkSettingsDesign>() {
    override suspend fun main() {
        val persistOverride = withClash { queryOverride(Clash.OverrideSlot.Persist) }
        val sessionOverride = withClash { queryOverride(Clash.OverrideSlot.Session) }
        val design = NetworkSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            clashRunning,
            persistOverride.allowLan ?: false,
            resolveLanAddresses(sessionOverride, persistOverride),
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
                        is NetworkSettingsDesign.Request.UpdateAllowLan ->
                            persistOverride.allowLan = it.enabled
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
        sessionOverride: ConfigurationOverride,
        persistOverride: ConfigurationOverride,
    ): List<String> {
        val port = resolveLanPort(sessionOverride, persistOverride)
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

        return addresses.distinct().sorted()
    }

    private fun resolveLanPort(
        sessionOverride: ConfigurationOverride,
        persistOverride: ConfigurationOverride,
    ): Int {
        return sessionOverride.mixedPort
            ?: persistOverride.mixedPort
            ?: sessionOverride.httpPort
            ?: persistOverride.httpPort
            ?: sessionOverride.socksPort
            ?: persistOverride.socksPort
            ?: 10801
    }
}
