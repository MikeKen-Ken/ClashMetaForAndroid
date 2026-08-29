package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.util.asSocketAddressText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class NetworkSnapshot(
    val network: Network?,
    val dnsList: List<String>,
)

private data class ObservedNetworkState(
    val network: Network?,
    val dnsList: List<String>,
    val route: NetworkRouteFingerprint?,
) {
    fun publicSnapshot() = NetworkSnapshot(network, dnsList)
}

private data class NetworkRouteFingerprint(
    val interfaceName: String?,
    val linkAddresses: Set<String>,
    val routes: Set<String>,
    val mtu: Int,
    val nat64Prefix: String?,
)

class NetworkObserveModule(service: Service) : Module<NetworkSnapshot>(service) {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }.build()

    private data class NetworkInfo(
        @Volatile var losingMs: Long = 0,
        @Volatile var dnsList: List<InetAddress> = emptyList(),
        @Volatile var route: NetworkRouteFingerprint? = null,
        @Volatile var capabilities: NetworkCapabilities? = null,
        @Volatile var blocked: Boolean = false,
    ) {
        fun isAvailable(): Boolean = losingMs < System.currentTimeMillis() && !blocked
    }

    private val networkInfos = ConcurrentHashMap<Network, NetworkInfo>()

    private fun infoFor(network: Network): NetworkInfo =
        networkInfos[network] ?: NetworkInfo().also { newInfo ->
            networkInfos.putIfAbsent(network, newInfo)
        }.let { networkInfos[network] ?: it }

    private fun signalChange() {
        changes.trySend(Unit)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("NetworkObserve onAvailable network=$network")
            infoFor(network).losingMs = 0
            signalChange()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            infoFor(network).capabilities = capabilities
            signalChange()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            Log.i("NetworkObserve onBlockedStatusChanged network=$network blocked=$blocked")
            infoFor(network).blocked = blocked
            signalChange()
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            Log.i("NetworkObserve onLosing network=$network")
            infoFor(network).losingMs = System.currentTimeMillis() + maxMsToLive
            signalChange()
        }

        override fun onLost(network: Network) {
            Log.i("NetworkObserve onLost network=$network")
            networkInfos.remove(network)
            signalChange()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            Log.i("NetworkObserve onLinkPropertiesChanged network=$network $linkProperties")
            infoFor(network).apply {
                dnsList = linkProperties.dnsServers
                route = linkProperties.routeSnapshot()
            }
            signalChange()
        }

        override fun onUnavailable() {
            Log.i("NetworkObserve onUnavailable")
            signalChange()
        }
    }

    private fun register() {
        Log.i("NetworkObserve start register")
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w("NetworkObserve register failed", e)
        }
    }

    private fun unregister() {
        Log.i("NetworkObserve start unregister")
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w("NetworkObserve unregister failed", e)
        }
    }

    private fun networkPriority(entry: Map.Entry<Network, NetworkInfo>): Int {
        val capabilities = entry.value.capabilities
        return when {
            capabilities == null -> 100
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 90
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> 5
            else -> 20
        }
    }

    private fun currentSnapshot(): ObservedNetworkState {
        val selected = networkInfos.entries.asSequence()
            .filter { it.value.isAvailable() }
            .minByOrNull(::networkPriority)
        val dnsList = selected?.value?.dnsList.orEmpty()
            .map { it.asSocketAddressText(53) }
        return ObservedNetworkState(selected?.key, dnsList, selected?.value?.route)
    }

    override suspend fun run() {
        register()

        var previous: NetworkSnapshot? = null
        signalChange()
        try {
            while (true) {
                changes.receive()
                delay(NETWORK_CHANGE_DEBOUNCE_MS)
                while (changes.tryReceive().isSuccess) {
                    // Coalesce callback bursts into one complete snapshot.
                }

                val snapshot = currentSnapshot()
                if (snapshot == previous) continue

                val routeChanged = previous?.network != snapshot.network ||
                    previous?.route != snapshot.route
                Log.i(
                    "NetworkObserve transition ${previous?.network} -> ${snapshot.network}, " +
                        "routeChanged=$routeChanged, dns=${snapshot.dnsList}",
                )
                previous = snapshot
                if (routeChanged) {
                    Clash.notifyNetworkChanged(snapshot.dnsList)
                    enqueueEvent(snapshot.publicSnapshot())
                } else {
                    Clash.notifyDnsChanged(snapshot.dnsList)
                }
            }
        } finally {
            unregister()
        }
    }

    companion object {
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 250L
    }
}

private fun LinkProperties.routeSnapshot() = NetworkRouteFingerprint(
    interfaceName = interfaceName,
    linkAddresses = linkAddresses.mapTo(mutableSetOf()) { it.toString() },
    routes = routes.mapTo(mutableSetOf()) { it.toString() },
    mtu = mtu,
    nat64Prefix = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        nat64Prefix?.toString()
    } else {
        null
    },
)
