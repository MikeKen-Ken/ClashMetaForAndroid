package com.github.kr328.clash.service.util

import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import java.util.*

fun Context.sendBroadcastSelf(intent: Intent) {
    sendBroadcast(
        intent.setPackage(this.packageName),
        Permissions.RECEIVE_SELF_BROADCASTS
    )
}

fun Context.sendProfileChanged(uuid: UUID) {
    val intent = Intent(Intents.ACTION_PROFILE_CHANGED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())

    sendBroadcastSelf(intent)
}

fun Context.sendProfileLoaded(uuid: UUID) {
    val intent = Intent(Intents.ACTION_PROFILE_LOADED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())

    sendBroadcastSelf(intent)
}

fun Context.sendProfileUpdateCompleted(uuid: UUID) {
    val intent = Intent(Intents.ACTION_PROFILE_UPDATE_COMPLETED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())

    sendBroadcastSelf(intent)
}

fun Context.sendProfileUpdateFailed(uuid: UUID, reason: String) {
    val intent = Intent(Intents.ACTION_PROFILE_UPDATE_FAILED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())
        .putExtra(Intents.EXTRA_FAIL_REASON, reason)

    sendBroadcastSelf(intent)
}

fun Context.sendOverrideChanged() {
    val intent = Intent(Intents.ACTION_OVERRIDE_CHANGED)

    sendBroadcastSelf(intent)
}

fun Context.sendProxyGroupRefresh(groupName: String) {
    sendBroadcastSelf(
        Intent(Intents.ACTION_PROXY_GROUP_REFRESH).putExtra(
            Intents.EXTRA_NAME,
            groupName
        )
    )
}

fun Context.sendServiceRecreated() {
    sendBroadcastSelf(Intent(Intents.ACTION_SERVICE_RECREATED))
}

fun Context.sendClashStarted() {
    sendBroadcastSelf(Intent(Intents.ACTION_CLASH_STARTED))
}

fun Context.sendClashStopped(reason: String?) {
    sendBroadcastSelf(
        Intent(Intents.ACTION_CLASH_STOPPED).putExtra(
            Intents.EXTRA_STOP_REASON,
            reason
        )
    )
}

/** 将一行诊断信息送到主进程 [com.github.kr328.clash.common.log.DebugLog]（调试界面轮询可见）。 */
fun Context.sendDebugUiLog(tag: String, message: String) {
    sendBroadcastSelf(
        Intent(Intents.ACTION_DEBUG_UI_LOG)
            .putExtra(Intents.EXTRA_DEBUG_LOG_TAG, tag.take(64))
            .putExtra(Intents.EXTRA_DEBUG_LOG_MESSAGE, message.take(900))
    )
}
