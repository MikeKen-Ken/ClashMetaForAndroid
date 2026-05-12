package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Context
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.persistSummaryForDebug
import com.github.kr328.clash.service.config.OverrideRuntimeApplier
import com.github.kr328.clash.service.util.sendDebugUiLog
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null

        reload.trySend(Unit)

        while (true) {
            val changed: UUID? = select {
                broadcasts.onReceive {
                    if (it.action == Intents.ACTION_PROFILE_CHANGED)
                        UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                    else
                        null
                }
                reload.onReceive {
                    null
                }
            }

            try {
                val current = store.activeProfile
                    ?: throw NullPointerException("No profile selected")

                if (current == loaded && changed != null && changed != loaded)
                    continue

                loaded = current

                val active = ImportedDao().queryByUUID(current)
                    ?: throw NullPointerException("No profile selected")

                // Write current profile's proxy selections to persist override so native
                // can apply them immediately after Load() (no race with UI).
                val selections = SelectionDao().querySelections(active.uuid)
                Clash.mutatePersistOverride { o ->
                    o.proxySelections = selections.associate { it.proxy to it.selected }
                }

                migratePersistedProxyModeIfNeeded(service, store)
                val override = Clash.queryOverride(Clash.OverrideSlot.Persist)
                runCatching {
                    service.sendDebugUiLog(
                        "配置加载",
                        "mutatePersist 后 Persist=${override.persistSummaryForDebug()} selections=${selections.size}",
                    )
                }
                val session = Clash.queryOverride(Clash.OverrideSlot.Session)
                session.mode = store.persistedProxyModeOrDefault()
                session.proxyDelayTestTimeoutMs = readProxyDelayTestTimeoutFromMainProcessPreferences(service)
                session.proxyDelayTestConcurrency = readProxyDelayTestConcurrencyFromMainProcessPreferences(service)
                // 网络/覆写/日志等 UI 主要写 Persist；Session 若残留非空会在合并时盖住 Persist，导致「退出再进又变回去」。
                override.logLevel?.let { session.logLevel = it }
                override.allowLan?.let { session.allowLan = it }
                override.bindAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { session.bindAddress = it }
                override.tun.strictRoute?.let { session.tun.strictRoute = it }
                Clash.patchOverride(Clash.OverrideSlot.Session, session)

                Clash.setHealthCheckWorkerLimit(session.proxyDelayTestConcurrency ?: 30)

                Clash.load(service.importedDir.resolve(active.uuid.toString())).await()

                OverrideRuntimeApplier.applyPersistRuntimeLightFieldsAfterFullLoad()

                val remove = SelectionDao().querySelections(active.uuid)
                    .filterNot { Clash.patchSelector(it.proxy, it.selected) }
                    .map { it.proxy }

                SelectionDao().removeSelections(active.uuid, remove)

                StatusProvider.currentProfile = active.name

                service.sendProfileLoaded(current)

                Log.d("Profile ${active.name} loaded")
            } catch (e: Exception) {
                return enqueueEvent(LoadException(e.message ?: "Unknown"))
            }
        }
    }
}

private const val UI_PREF_NAME = "ui"
private const val UI_PREF_KEY_PROXY_MODE = "proxy_ui_mode"
private const val UI_PREF_KEY_PROXY_DELAY_TEST_TIMEOUT_MS = "proxy_delay_test_timeout_ms"
private const val UI_PREF_KEY_PROXY_DELAY_TEST_CONCURRENCY = "proxy_delay_test_concurrency"

/** 与代理页选项一致：30 / 50 / 100 / 150 / 200；历史 10/20/40 迁入为 30。 */
private fun normalizeProxyDelayTestConcurrency(raw: Int): Int = when (raw) {
    30, 50, 100, 150, 200 -> raw
    10, 20, 40 -> 30
    else -> 30
}

/** 首次将主进程 UiStore 中的模式迁入 service 偏好（与 UiStore 的 key 保持一致）。 */
private fun migratePersistedProxyModeIfNeeded(context: Context, store: ServiceStore) {
    if (store.persistedProxyUiMode.isNotEmpty()) return
    val mode = readProxyUiModeFromMainProcessPreferences(context) ?: TunnelState.Mode.Rule
    store.persistedProxyUiMode = mode.name
}

@Suppress("DEPRECATION")
private fun readProxyUiModeFromMainProcessPreferences(context: Context): TunnelState.Mode? {
    val prefs = context.applicationContext.getSharedPreferences(UI_PREF_NAME, Context.MODE_MULTI_PROCESS)
    val name = prefs.getString(UI_PREF_KEY_PROXY_MODE, null) ?: return null
    return TunnelState.Mode.values().find { it.name == name }
}

@Suppress("DEPRECATION")
private fun readProxyDelayTestTimeoutFromMainProcessPreferences(context: Context): Int? {
    val prefs = context.applicationContext.getSharedPreferences(UI_PREF_NAME, Context.MODE_MULTI_PROCESS)
    val timeout = prefs.getInt(UI_PREF_KEY_PROXY_DELAY_TEST_TIMEOUT_MS, 0)
    return if (timeout > 0) timeout else null
}

@Suppress("DEPRECATION")
private fun readProxyDelayTestConcurrencyFromMainProcessPreferences(context: Context): Int {
    val prefs = context.applicationContext.getSharedPreferences(UI_PREF_NAME, Context.MODE_MULTI_PROCESS)
    val raw = prefs.getInt(UI_PREF_KEY_PROXY_DELAY_TEST_CONCURRENCY, 30)
    return normalizeProxyDelayTestConcurrency(raw)
}