package com.github.kr328.clash.connectivitysync

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.connectivitysync.publishConnectivitySyncSettings
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Copies WebDAV settings into the VPN process. The background service cannot trust the UI
 * preference file, and the merge timer lives in that process.
 */
object ConnectivitySyncSettingsMirror {
    private const val PUSH_INTERVAL_MS = 15_000L

    private var visible = false
    private var job: Job? = null

    fun onAppVisibleChanged(context: Context, visible: Boolean) {
        this.visible = visible
        if (visible) push(context)
        if (!visible || job?.isActive == true) return
        val appContext = context.applicationContext
        job = Global.launch(Dispatchers.Main) {
            while (isActive && this@ConnectivitySyncSettingsMirror.visible) {
                push(appContext)
                delay(PUSH_INTERVAL_MS)
            }
        }
    }

    fun push(context: Context) {
        val ui = UiStore(context)
        ServiceStore(context).publishConnectivitySyncSettings(
            url = ui.webdavUrl,
            username = ui.webdavUsername,
            password = ui.webdavPassword,
            intervalHours = ui.connectivitySyncIntervalHours,
        )
    }
}
