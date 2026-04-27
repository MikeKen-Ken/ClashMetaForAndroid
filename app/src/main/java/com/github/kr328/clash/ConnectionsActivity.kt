package com.github.kr328.clash

import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ConnectionsDesign
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

class ConnectionsActivity : BaseActivity<ConnectionsDesign>() {
    private val blockedIps = linkedSetOf<String>()

    override fun onStop() {
        (design as? ConnectionsDesign)?.let { d ->
            launch { d.persistState() }
        }
        super.onStop()
    }

    override suspend fun main() {
        val design = ConnectionsDesign(this, uiStore)
        setContentDesign(design)
        blockedIps.clear()
        blockedIps.addAll(
            withClash {
                queryOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Persist)
                    .app
                    .lanBlockedDevices
                    .orEmpty()
            }
        )
        uiStore.lanBlockedSourceIps = blockedIps.toSet()

        refreshConnections(design)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> launch { refreshConnections(design) }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        is ConnectionsDesign.Request.CloseConnection -> {
                            withClash { closeConnection(it.connection.id) }
                            launch { refreshConnections(design) }
                        }
                        is ConnectionsDesign.Request.DisableDevice -> {
                            blockedIps.add(it.sourceIp)
                            uiStore.lanBlockedSourceIps = blockedIps.toSet()
                            withClash {
                                val persist = queryOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Persist)
                                persist.app.lanBlockedDevices = blockedIps.toList()
                                patchOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Persist, persist)
                            }
                            launch { refreshConnections(design) }
                        }
                        is ConnectionsDesign.Request.EnableDevice -> {
                            blockedIps.remove(it.sourceIp)
                            uiStore.lanBlockedSourceIps = blockedIps.toSet()
                            withClash {
                                val persist = queryOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Persist)
                                persist.app.lanBlockedDevices = blockedIps.toList()
                                patchOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Persist, persist)
                            }
                            launch { refreshConnections(design) }
                        }
                        ConnectionsDesign.Request.ClearClosedConnections -> {
                            design.clearClosedConnections()
                        }
                        ConnectionsDesign.Request.CloseAllConnections -> {
                            withClash { closeAllConnections() }
                            launch { refreshConnections(design) }
                        }
                        ConnectionsDesign.Request.CloseConnectionsExcludingDirect -> {
                            withClash { closeConnectionsExcludingDirect() }
                            launch { refreshConnections(design) }
                        }
                    }
                }
                ticker.onReceive {
                    launch { refreshConnections(design) }
                }
            }
        }
    }

    private suspend fun refreshConnections(design: ConnectionsDesign) {
        try {
            val snapshot = withClash { queryConnections() }
            if (blockedIps.isNotEmpty()) {
                withClash {
                    snapshot.connections
                        .filter { conn -> conn.metadata?.sourceIP in blockedIps }
                        .forEach { conn -> closeConnection(conn.id) }
                }
            }
            val filtered = if (blockedIps.isEmpty()) {
                snapshot
            } else {
                snapshot.copy(
                    connections = snapshot.connections.filterNot { conn ->
                        conn.metadata?.sourceIP in blockedIps
                    }
                )
            }
            design.patchConnections(filtered)
        } catch (_: Exception) {
            design.patchConnections(
                com.github.kr328.clash.core.model.ConnectionsSnapshot(connections = emptyList())
            )
        }
    }
}
