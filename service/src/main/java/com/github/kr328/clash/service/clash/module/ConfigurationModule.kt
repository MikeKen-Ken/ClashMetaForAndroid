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
                val override = Clash.queryOverride(Clash.OverrideSlot.Persist)
                override.proxySelections = selections.associate { it.proxy to it.selected }
                Clash.patchOverride(Clash.OverrideSlot.Persist, override)

                migratePersistedProxyModeIfNeeded(service, store)
                val session = Clash.queryOverride(Clash.OverrideSlot.Session)
                session.mode = store.persistedProxyModeOrDefault()
                session.proxyDelayTestTimeoutMs = readProxyDelayTestTimeoutFromMainProcessPreferences(service)
                Clash.patchOverride(Clash.OverrideSlot.Session, session)

                Clash.load(service.importedDir.resolve(active.uuid.toString())).await()

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