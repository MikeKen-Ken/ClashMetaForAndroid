package com.github.kr328.clash.service.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components

/**
 * Opens the app through its launcher task without clearing activities above
 * [Components.MAIN_ACTIVITY]. This resumes the panel the user last visited.
 */
fun Context.mainTaskPendingIntent(requestCode: Int): PendingIntent {
    val intent = packageManager.getLaunchIntentForPackage(packageName)
        ?: Intent.makeMainActivity(Components.MAIN_ACTIVITY)

    intent.flags = (intent.flags or
        Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) and
        Intent.FLAG_ACTIVITY_CLEAR_TOP.inv()

    return PendingIntent.getActivity(
        this,
        requestCode,
        intent,
        pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
    )
}
