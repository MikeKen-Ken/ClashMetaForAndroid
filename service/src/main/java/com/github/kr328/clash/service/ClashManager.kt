package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.config.ConfigurationOverrideClassifier
import com.github.kr328.clash.service.config.OverrideReloadDecision
import com.github.kr328.clash.service.config.OverrideRuntimeApplier
import com.github.kr328.clash.service.util.persistSummaryForDebug
import com.github.kr328.clash.service.util.sendDebugUiLog
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.File

private const val DBG_TAG_OVERRIDE = "覆写Persist"

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private var logReceiver: ReceiveChannel<LogMessage>? = null

    override fun queryTunnelState(): TunnelState {
        return Clash.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun queryRuntimeYamlByProfile(profilePath: String): String {
        return Clash.queryRuntimeYamlByProfile(File(profilePath))
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    override fun patchSelector(group: String, name: String): Boolean {
        return Clash.patchSelector(group, name).also {
            val current = store.activeProfile ?: return@also

            if (it) {
                SelectionDao().setSelected(Selection(current, group, name))
            } else {
                SelectionDao().removeSelected(current, group)
            }
        }
    }

    override fun setHealthCheckWorkerLimit(limit: Int) {
        Clash.setHealthCheckWorkerLimit(limit)
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        if (slot == Clash.OverrideSlot.Persist) {
            val outcome = Clash.withPersistOverrideSync {
                val previous = Clash.queryOverride(Clash.OverrideSlot.Persist)
                val decision =
                    ConfigurationOverrideClassifier.classify(slot, previous, configuration)
                Clash.patchOverride(Clash.OverrideSlot.Persist, configuration)
                previous to decision
            }
            val previous = outcome.first
            val decision = outcome.second
            runCatching {
                context.sendDebugUiLog(
                    DBG_TAG_OVERRIDE,
                    "Binder patchPersist decision=$decision 旧=${previous.persistSummaryForDebug()} 新=${configuration.persistSummaryForDebug()}",
                )
            }
            when (decision) {
                OverrideReloadDecision.FullConfigurationReload -> context.sendOverrideChanged()
                OverrideReloadDecision.RuntimeLightOnly ->
                    OverrideRuntimeApplier.apply(previous, configuration)
            }
            // 与 ConfigurationModule 一致：运行配置/导出 YAML 合并时 Session 会参与；
            // 仅写 Persist 不同步 Session 会导致「已开局域网但运行配置仍 allow-lan: false」直到下次整包 load。
            syncPersistConnectivityHintsToSession(configuration)
            return
        }

        val previous = queryOverride(slot)
        val decision = ConfigurationOverrideClassifier.classify(slot, previous, configuration)

        Clash.patchOverride(slot, configuration)

        if (slot == Clash.OverrideSlot.Session) {
            configuration.mode?.let { store.persistedProxyUiMode = it.name }
        }

        when (decision) {
            OverrideReloadDecision.FullConfigurationReload -> context.sendOverrideChanged()
            OverrideReloadDecision.RuntimeLightOnly -> OverrideRuntimeApplier.apply(previous, configuration)
        }
    }

    /**
     * 将 Persist 中与入站相关的字段镜像到 Session，语义对齐 [ConfigurationModule] 在 load 前的处理。
     * 避免仅 Binder 更新 Persist 时 Session 残留旧 allow-lan/bind-address。
     */
    private fun syncPersistConnectivityHintsToSession(persist: ConfigurationOverride) {
        val prevSession = Clash.queryOverride(Clash.OverrideSlot.Session)
        var nextSession = prevSession
        var dirty = false
        persist.allowLan?.let { v ->
            if (nextSession.allowLan != v) {
                nextSession = nextSession.copy(allowLan = v)
                dirty = true
            }
        }
        persist.bindAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
            if (nextSession.bindAddress?.trim().orEmpty() != v) {
                nextSession = nextSession.copy(bindAddress = v)
                dirty = true
            }
        }
        if (!dirty) return
        patchOverride(Clash.OverrideSlot.Session, nextSession)
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
    }

    override fun queryConnections(): ConnectionsSnapshot {
        return Clash.queryConnections()
    }

    override fun closeConnection(id: String): Boolean {
        return Clash.closeConnection(id)
    }

    override fun closeAllConnections() {
        Clash.closeAllConnections()
    }

    override fun closeConnectionsExcludingDirect() {
        Clash.closeConnectionsExcludingDirect()
    }

    override fun closeConnectionsUsingProxyGroup(group: String) {
        Clash.closeConnectionsUsingProxyGroup(group)
    }

    override fun closeLanConnections() {
        Clash.closeLanConnections()
    }

    override fun flushFakeIpCache() {
        Clash.flushFakeIpCache()
    }

    override suspend fun healthCheck(group: String) {
        Clash.healthCheck(group).await()
        store.activeProfile?.let { uuid ->
            SelectionDao().removeSelected(uuid, group)
        }
    }

    override suspend fun healthCheckWithTimeout(group: String, timeoutMs: Int, concurrency: Int) {
        Clash.healthCheckWithTimeout(group, timeoutMs, concurrency).await()
        store.activeProfile?.let { uuid ->
            SelectionDao().removeSelected(uuid, group)
        }
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()

                Clash.forceGc()
            }

            if (observer != null) {
                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                            // ignore
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }
}