package com.github.kr328.clash

import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ConnectionsDesign
import com.github.kr328.clash.util.scheduleClashMutation
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Job
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
        design.loadPersistedClosedEntries()
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

        var refreshJob: Job? = null
        var refreshPending = false
        fun requestRefresh() {
            if (refreshJob?.isActive == true) {
                refreshPending = true
                return
            }
            refreshJob = launch {
                do {
                    refreshPending = false
                    refreshConnections(design)
                } while (refreshPending && isActive)
            }
        }

        requestRefresh()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> requestRefresh()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        is ConnectionsDesign.Request.CloseConnection -> {
                            withClash { closeConnection(it.connection.id) }
                            requestRefresh()
                        }
                        is ConnectionsDesign.Request.DisconnectDevice -> {
                            withClash {
                                queryConnections().connections
                                    .filter { conn -> conn.metadata?.sourceIP == it.sourceIp }
                                    .forEach { conn -> closeConnection(conn.id) }
                            }
                            requestRefresh()
                        }
                        is ConnectionsDesign.Request.DisableDevice -> {
                            blockedIps.add(it.sourceIp)
                            uiStore.lanBlockedSourceIps = blockedIps.toSet()
                            scheduleClashMutation("连接管理") {
                                val persist = queryOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Persist)
                                persist.app.lanBlockedDevices = blockedIps.toList()
                                patchOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Persist, persist)
                            }
                            requestRefresh()
                        }
                        is ConnectionsDesign.Request.EnableDevice -> {
                            blockedIps.remove(it.sourceIp)
                            uiStore.lanBlockedSourceIps = blockedIps.toSet()
                            scheduleClashMutation("连接管理") {
                                val persist = queryOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Persist)
                                persist.app.lanBlockedDevices = blockedIps.toList()
                                patchOverride(com.github.kr328.clash.core.Clash.OverrideSlot.Persist, persist)
                            }
                            requestRefresh()
                        }
                        ConnectionsDesign.Request.ClearClosedConnections -> {
                            design.clearClosedConnections()
                        }
                        ConnectionsDesign.Request.CloseAllConnections -> {
                            withClash { closeAllConnections() }
                            requestRefresh()
                        }
                        ConnectionsDesign.Request.CloseConnectionsExcludingDirect -> {
                            withClash { closeConnectionsExcludingDirect() }
                            requestRefresh()
                        }
                    }
                }
                ticker.onReceive {
                    requestRefresh()
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
