package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.model.DarkMode
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

            clickable(
                title = R.string.background_image,
                icon = R.drawable.ic_baseline_image,
                summary = if (hasBackground) {
                    R.string.background_image_set
                } else {
                    R.string.background_image_none
                },
            ) {
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
}