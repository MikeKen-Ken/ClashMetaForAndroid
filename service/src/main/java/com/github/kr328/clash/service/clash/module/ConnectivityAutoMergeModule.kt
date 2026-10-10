package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.connectivitysync.ConnectivityStatsSync
import com.github.kr328.clash.service.connectivitysync.ConnectivitySyncBackoff
import com.github.kr328.clash.service.connectivitysync.ConnectivitySyncSettings
import com.github.kr328.clash.service.connectivitysync.ConnectivitySyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Merges connectivity statistics on the saved interval for as long as this service is running. */
class ConnectivityAutoMergeModule(service: Service) : Module<Unit>(service) {
    override suspend fun run() {
        ConnectivitySyncStatus.currentJson(service)
        while (true) {
            runOnce()
            delay(TimeUnit.MINUTES.toMillis(1))
        }
    }

    private suspend fun runOnce() {
        val settings = ConnectivitySyncSettings.load(service)
        ConnectivitySyncBackoff.rememberSettings(settings)
        if (!ConnectivityStatsSync.isConfigured(settings)) return
        if (!ConnectivityStatsSync.isDue(service, settings.intervalHours)) return
        if (!ConnectivitySyncBackoff.isOpen()) return
        try {
            ConnectivityStatsSync.merge(service, settings)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w("Automatic connectivity merge failed: ${error.message}", error)
            ConnectivitySyncBackoff.noteFailure()
        }
    }
}
