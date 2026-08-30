package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.model.WallpaperPlaybackMode
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.UiBackground
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ServiceStore

class AppSettingsDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceStore,
    behavior: Behavior,
    running: Boolean,
    onHideIconChange: (hide: Boolean) -> Unit,
) : Design<AppSettingsDesign.Request>(context) {
    enum class Request {
        ReCreateAllActivities,
        PickBackground,
        ClearBackground,
        UploadWallpapers,
        DownloadWallpapers,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.behavior)

            switch(
                value = behavior::autoRestart,
                icon = R.drawable.ic_baseline_restore,
                title = R.string.auto_restart,
                summary = R.string.allow_clash_auto_restart,
            )

            category(R.string.interface_)

            selectableList(
                value = uiStore::darkMode,
                values = DarkMode.values(),
                valuesText = arrayOf(
                    R.string.follow_system_android_10,
                    R.string.always_light,
                    R.string.always_dark
                ),
                icon = R.drawable.ic_baseline_brightness_4,
                title = R.string.dark_mode
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            switch(
                value = uiStore::hideAppIcon,
                icon = R.drawable.ic_baseline_hide,
                title = R.string.hide_app_icon_title,
                summary = R.string.hide_app_icon_desc,
            ) {
                listener = OnChangedListener {
                    onHideIconChange(uiStore::hideAppIcon.get())
                }
            }

            val hasBackground = UiBackground.exists(context)
            val playbackHolder = PlaybackHolder(context)
            val intervalHolder = IntervalHolder(context)

            clickable(
                title = R.string.background_image,
                icon = R.drawable.ic_baseline_image,
            ) {
                summary = if (hasBackground) {
                    context.getString(R.string.background_image_set, UiBackground.count(context))
                } else {
                    context.getString(R.string.background_image_none)
                }
                clicked {
                    requests.trySend(Request.PickBackground)
                }
            }

            clickable(
                title = R.string.background_image_clear,
                icon = R.drawable.ic_baseline_hide,
                summary = R.string.background_image_clear_summary,
            ) {
                view.visibility = if (hasBackground) View.VISIBLE else View.GONE
                clicked {
                    requests.trySend(Request.ClearBackground)
                }
            }

            selectableList(
                value = playbackHolder::mode,
                values = WallpaperPlaybackMode.values(),
                valuesText = arrayOf(
                    R.string.background_playback_fixed,
                    R.string.background_playback_random,
                ),
                icon = R.drawable.ic_baseline_replay,
                title = R.string.background_playback,
            ) {
                view.visibility = if (hasBackground) View.VISIBLE else View.GONE
                listener = OnChangedListener {
                    UiBackground.scheduleRotation(context)
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            selectableList(
                value = intervalHolder::seconds,
                values = UiBackground.intervalOptions,
                valuesText = arrayOf(
                    R.string.background_interval_30s,
                    R.string.background_interval_1m,
                    R.string.background_interval_5m,
                    R.string.background_interval_15m,
                    R.string.background_interval_1h,
                ),
                title = R.string.background_interval,
            ) {
                view.visibility = if (hasBackground) View.VISIBLE else View.GONE
                listener = OnChangedListener {
                    UiBackground.scheduleRotation(context)
                }
            }

            selectableList(
                value = uiStore::backgroundOverlayPercent,
                values = UiBackground.overlayPercents,
                valuesText = arrayOf(
                    R.string.background_overlay_none,
                    R.string.background_overlay_light,
                    R.string.background_overlay_medium,
                    R.string.background_overlay_strong,
                ),
                icon = R.drawable.ic_baseline_brightness_4,
                title = R.string.background_overlay,
            ) {
                view.visibility = if (hasBackground) View.VISIBLE else View.GONE
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            selectableList(
                value = uiStore::cardSurfaceOpacityPercent,
                values = UiBackground.cardOpacityPercents,
                valuesText = arrayOf(
                    R.string.card_surface_opacity_100,
                    R.string.card_surface_opacity_85,
                    R.string.card_surface_opacity_70,
                    R.string.card_surface_opacity_50,
                ),
                title = R.string.card_surface_opacity,
            ) {
                view.visibility = if (hasBackground) View.VISIBLE else View.GONE
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            category(R.string.webdav_sync)

            editableText(
                value = uiStore::webdavUrl,
                adapter = StoredStringAdapter,
                icon = R.drawable.ic_baseline_public,
                title = R.string.webdav_url,
                placeholder = R.string.webdav_not_set,
            )

            editableText(
                value = uiStore::webdavUsername,
                adapter = StoredStringAdapter,
                icon = R.drawable.ic_baseline_assignment,
                title = R.string.webdav_username,
                placeholder = R.string.webdav_not_set,
            )

            editableText(
                value = uiStore::webdavPassword,
                adapter = StoredStringAdapter,
                icon = R.drawable.ic_baseline_vpn_lock,
                title = R.string.webdav_password,
                placeholder = R.string.webdav_not_set,
                secret = true,
                secretSummary = R.string.webdav_password_set,
            )

            clickable(
                title = R.string.webdav_upload_wallpapers,
                icon = R.drawable.ic_baseline_publish,
                summary = R.string.webdav_upload_wallpapers_summary,
            ) {
                clicked {
                    requests.trySend(Request.UploadWallpapers)
                }
            }

            clickable(
                title = R.string.webdav_download_wallpapers,
                icon = R.drawable.ic_baseline_cloud_download,
                summary = R.string.webdav_download_wallpapers_summary,
            ) {
                clicked {
                    requests.trySend(Request.DownloadWallpapers)
                }
            }

            category(R.string.service)

            switch(
                value = srvStore::dynamicNotification,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.show_traffic,
                summary = R.string.show_traffic_summary
            ) {
                enabled = !running
            }
        }

        binding.content.addView(screen.root)
    }

    private class PlaybackHolder(private val context: Context) {
        var mode: WallpaperPlaybackMode
            get() = UiBackground.playbackMode(context)
            set(value) {
                UiBackground.setPlaybackMode(context, value)
            }
    }

    private class IntervalHolder(private val context: Context) {
        var seconds: Int
            get() = UiBackground.intervalSeconds(context)
            set(value) {
                UiBackground.setIntervalSeconds(context, value)
            }
    }

    companion object {
        private val StoredStringAdapter = object : NullableTextAdapter<String> {
            override fun from(value: String): String? = value.ifBlank { null }
            override fun to(text: String?): String = text?.trim().orEmpty()
        }
    }
}