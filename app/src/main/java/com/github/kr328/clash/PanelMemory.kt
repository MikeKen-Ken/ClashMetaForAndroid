package com.github.kr328.clash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Remembers which panel was in front, and whether the user just pressed back.
 * Screens that need launch extras are omitted so a restore cannot open them empty.
 */
object PanelMemory {
    private var backAtMs: Long = 0L

    fun noteUserBack(nowMs: Long = SystemClock.elapsedRealtime()) {
        backAtMs = nowMs
    }

    fun userNavigatedBack(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        return PanelRestore.userBackIsFresh(backAtMs, nowMs)
    }

    fun idOf(activity: Activity): AppPanel? = when (activity) {
        is MainActivity -> AppPanel.Main
        is ProxyActivity -> AppPanel.Proxy
        is ProfilesActivity -> AppPanel.Profiles
        is ProvidersActivity -> AppPanel.Providers
        is LogsActivity -> AppPanel.Logs
        is ConnectionsActivity -> AppPanel.Connections
        is NetworkSettingsActivity -> AppPanel.Network
        is AppSettingsActivity -> AppPanel.App
        is DebugActivity -> AppPanel.Debug
        is RunningConfigActivity -> AppPanel.RunningConfig
        is ConnectivityStatsActivity -> AppPanel.ConnectivityStats
        is SettingsActivity -> AppPanel.Settings
        is AccessControlActivity -> AppPanel.AccessControl
        is HelpActivity -> AppPanel.Help
        is MetaFeatureSettingsActivity -> AppPanel.MetaFeatures
        is OverrideSettingsActivity -> AppPanel.Override
        is NewProfileActivity -> AppPanel.NewProfile
        else -> null
    }

    fun intent(context: Context, panel: AppPanel): Intent {
        val type = when (panel) {
            AppPanel.Main -> MainActivity::class.java
            AppPanel.Proxy -> ProxyActivity::class.java
            AppPanel.Profiles -> ProfilesActivity::class.java
            AppPanel.Providers -> ProvidersActivity::class.java
            AppPanel.Logs -> LogsActivity::class.java
            AppPanel.Connections -> ConnectionsActivity::class.java
            AppPanel.Network -> NetworkSettingsActivity::class.java
            AppPanel.App -> AppSettingsActivity::class.java
            AppPanel.Debug -> DebugActivity::class.java
            AppPanel.RunningConfig -> RunningConfigActivity::class.java
            AppPanel.ConnectivityStats -> ConnectivityStatsActivity::class.java
            AppPanel.Settings -> SettingsActivity::class.java
            AppPanel.AccessControl -> AccessControlActivity::class.java
            AppPanel.Help -> HelpActivity::class.java
            AppPanel.MetaFeatures -> MetaFeatureSettingsActivity::class.java
            AppPanel.Override -> OverrideSettingsActivity::class.java
            AppPanel.NewProfile -> NewProfileActivity::class.java
        }
        return Intent(context, type)
    }
}
