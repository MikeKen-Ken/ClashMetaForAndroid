package com.github.kr328.clash.connectivitysync

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Process-wide automatic merge. Runs while any app screen is visible, because the
 * remote service binding and clash-running state only exist in that window.
 */
object ConnectivityAutoMerge {
    private val checkIntervalMs = TimeUnit.MINUTES.toMillis(1)

    // Only touched on the main thread (visibility callbacks and the Main-dispatched loop).
    private var visible = false
    private var job: Job? = null

    fun onAppVisibleChanged(context: Context, visible: Boolean) {
        this.visible = visible
        if (!visible || job?.isActive == true) return
        val appContext = context.applicationContext
        job = Global.launch(Dispatchers.Main) {
            while (isActive && this@ConnectivityAutoMerge.visible) {
                runOnce(appContext)
                delay(checkIntervalMs)
            }
        }
    }

    private suspend fun runOnce(context: Context) {
        val store = UiStore(context)
        ConnectivitySyncBackoff.rememberSettings(store)
        if (!Remote.broadcasts.clashRunning || !ConnectivityStatsSync.isConfigured(store)) return
        if (!ConnectivityStatsSync.isDue(context, store.connectivitySyncIntervalHours)) return
        if (!ConnectivitySyncBackoff.isOpen()) return
        try {
            ConnectivityStatsSync.merge(
                context = context,
                store = store,
                mergeLocal = { previousOthers, remoteOthers, resetWatermarks ->
                    withClash {
                        mergeProxyConnectivityStats(previousOthers, remoteOthers, resetWatermarks)
                    }
                },
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.w("Automatic connectivity merge failed: ${error.message}", error)
            ConnectivitySyncBackoff.noteFailure()
        }
    }
}
