package com.github.kr328.clash

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.design.AppSettingsDesign
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.UiBackground
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.ApplicationObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import com.github.kr328.clash.design.R

class AppSettingsActivity : BaseActivity<AppSettingsDesign>(), Behavior {
    override suspend fun main() {
        val design = AppSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            this,
            clashRunning,
            ::onHideIconChange,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        AppSettingsDesign.Request.ReCreateAllActivities -> {
                            recreateActivities()
                        }
                        AppSettingsDesign.Request.PickBackground -> {
                            val uris = startActivityForResult(
                                ActivityResultContracts.GetMultipleContents(),
                                "image/*",
                            )
                            if (uris.isNotEmpty()) {
                                val imported = withContext(Dispatchers.IO) {
                                    uris.count { uri ->
                                        UiBackground.import(this@AppSettingsActivity, uri)
                                    }
                                }
                                if (imported > 0) {
                                    recreateActivities()
                                } else {
                                    design.showToast(
                                        R.string.background_image_import_failed,
                                        ToastDuration.Long,
                                    )
                                }
                            }
                        }
                        AppSettingsDesign.Request.ClearBackground -> {
                            UiBackground.clear(this@AppSettingsActivity)
                            recreateActivities()
                        }
                        AppSettingsDesign.Request.UploadWallpapers -> {
                            syncWallpapers(design, upload = true)
                        }
                        AppSettingsDesign.Request.DownloadWallpapers -> {
                            syncWallpapers(design, upload = false)
                        }
                    }
                }
            }
        }
    }

    override var autoRestart: Boolean
        get() {
            val status = packageManager.getComponentEnabledSetting(
                RestartReceiver::class.componentName
            )

            return status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        set(value) {
            val status = if (value)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            packageManager.setComponentEnabledSetting(
                RestartReceiver::class.componentName,
                status,
                PackageManager.DONT_KILL_APP,
            )
        }

    private fun onHideIconChange(hide: Boolean) {
        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        packageManager.setComponentEnabledSetting(
            ComponentName(this, mainActivityAlias),
            newState,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun recreateActivities() {
        ApplicationObserver.createdActivities.forEach {
            it.recreate()
        }
    }

    private suspend fun syncWallpapers(
        design: AppSettingsDesign,
        upload: Boolean,
    ) {
        if (!WallpaperWebDav.isConfigured(uiStore)) {
            design.showToast(R.string.webdav_not_configured, ToastDuration.Long)
            return
        }
        try {
            if (upload) {
                val bytes = withContext(Dispatchers.IO) {
                    UiBackground.encodePack(this@AppSettingsActivity)
                } ?: run {
                    design.showToast(R.string.webdav_pack_empty, ToastDuration.Long)
                    return
                }
                WallpaperWebDav.upload(uiStore, bytes)
                design.showToast(R.string.webdav_upload_ok, ToastDuration.Long)
            } else {
                val bytes = WallpaperWebDav.download(uiStore)
                val applied = withContext(Dispatchers.IO) {
                    UiBackground.applyPack(this@AppSettingsActivity, bytes)
                }
                if (applied) {
                    recreateActivities()
                    design.showToast(R.string.webdav_download_ok, ToastDuration.Long)
                } else {
                    design.showToast(R.string.webdav_sync_failed, ToastDuration.Long)
                }
            }
        } catch (_: Exception) {
            design.showToast(R.string.webdav_sync_failed, ToastDuration.Long)
        }
    }
}
