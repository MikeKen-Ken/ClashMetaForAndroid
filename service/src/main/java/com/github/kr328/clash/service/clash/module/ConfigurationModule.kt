package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Context
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.UiPreferences
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.Imported
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

                Clash.load(service.importedDir.resolve(active.uuid.toString())).await()

                syncSelectorsFromDb(active.uuid)

                // ClashRuntime clears session override on each start; mode lives only in UiStore prefs.
                reloadIfSavedProxyModeNonRule(active)

                StatusProvider.currentProfile = active.name

                service.sendProfileLoaded(current)

                Log.d("Profile ${active.name} loaded")
            } catch (e: Exception) {
                return enqueueEvent(LoadException(e.message ?: "Unknown"))
            }
        }
    }

    private suspend fun syncSelectorsFromDb(profileUuid: UUID) {
        val remove = SelectionDao().querySelections(profileUuid)
            .filterNot { Clash.patchSelector(it.proxy, it.selected) }
            .map { it.proxy }

        SelectionDao().removeSelections(profileUuid, remove)
    }

    private fun readSavedProxyUiMode(): TunnelState.Mode {
        val prefs = service.applicationContext.getSharedPreferences(
            UiPreferences.PREFERENCE_NAME,
            Context.MODE_PRIVATE,
        )
        val name = prefs.getString(UiPreferences.KEY_PROXY_UI_MODE, TunnelState.Mode.Rule.name)
            ?: TunnelState.Mode.Rule.name
        return TunnelState.Mode.values().find { it.name == name } ?: TunnelState.Mode.Rule
    }

    private suspend fun reloadIfSavedProxyModeNonRule(active: Imported) {
        val mode = readSavedProxyUiMode()
        if (mode == TunnelState.Mode.Rule) return

        val session = Clash.queryOverride(Clash.OverrideSlot.Session)
        session.mode = mode
        Clash.patchOverride(Clash.OverrideSlot.Session, session)

        Clash.load(service.importedDir.resolve(active.uuid.toString())).await()
        syncSelectorsFromDb(active.uuid)
    }
}