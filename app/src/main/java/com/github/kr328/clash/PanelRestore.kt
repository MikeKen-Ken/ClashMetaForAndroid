package com.github.kr328.clash

/**
 * Chooses whether the main screen should reopen a panel the user left open.
 * A task reset, process restart, or launcher/notification return can show the
 * main screen even though the user never pressed back.
 */
object PanelRestore {
    const val USER_BACK_WINDOW_MS = 2_000L

    fun panelToReopen(
        visible: AppPanel,
        remembered: AppPanel?,
        userNavigatedBack: Boolean,
    ): AppPanel? {
        if (userNavigatedBack || visible != AppPanel.Main) return null
        val target = remembered ?: return null
        if (target == AppPanel.Main) return null
        return target
    }

    fun userBackIsFresh(backAtMs: Long, nowMs: Long, windowMs: Long = USER_BACK_WINDOW_MS): Boolean {
        if (backAtMs <= 0L) return false
        val age = nowMs - backAtMs
        return age in 0..windowMs
    }
}

enum class AppPanel(val storageId: String) {
    Main("main"),
    Proxy("proxy"),
    Profiles("profiles"),
    Providers("providers"),
    Logs("logs"),
    Connections("connections"),
    Network("network"),
    App("app"),
    Debug("debug"),
    RunningConfig("running_config"),
    ConnectivityStats("connectivity_stats"),
    Settings("settings"),
    AccessControl("access_control"),
    Help("help"),
    MetaFeatures("meta_features"),
    Override("override"),
    NewProfile("new_profile"),
    ;

    companion object {
        fun fromId(id: String): AppPanel? = entries.firstOrNull { it.storageId == id }
    }
}
