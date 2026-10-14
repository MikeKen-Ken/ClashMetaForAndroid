package com.github.kr328.clash.service.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components

/**
 * Opens the app through its launcher task without clearing activities above
 * [Components.MAIN_ACTIVITY]. [Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED] would
 * drop those activities and land the user on the main screen.
 */
fun Context.mainTaskPendingIntent(requestCode: Int): PendingIntent {
    val intent = packageManager.getLaunchIntentForPackage(packageName)
        ?: Intent.makeMainActivity(Components.MAIN_ACTIVITY)

    intent.flags = flagsToResumeExistingTask(intent.flags)

    return PendingIntent.getActivity(
        this,
        requestCode,
        intent,
        pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
    )
}

internal fun flagsToResumeExistingTask(current: Int): Int {
    val discard =
        Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    return (current or Intent.FLAG_ACTIVITY_NEW_TASK) and discard.inv()
}
