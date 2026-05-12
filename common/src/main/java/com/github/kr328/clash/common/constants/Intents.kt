package com.github.kr328.clash.common.constants

import com.github.kr328.clash.common.util.packageName

object Intents {
    // Public
    val ACTION_PROVIDE_URL = "$packageName.action.PROVIDE_URL"
    val ACTION_START_CLASH = "$packageName.action.START_CLASH"
    val ACTION_STOP_CLASH = "$packageName.action.STOP_CLASH"
    val ACTION_TOGGLE_CLASH = "$packageName.action.TOGGLE_CLASH"

    const val EXTRA_NAME = "name"

    // Self
    val ACTION_SERVICE_RECREATED = "$packageName.intent.action.CLASH_RECREATED"
    val ACTION_CLASH_STARTED = "$packageName.intent.action.CLASH_STARTED"
    val ACTION_CLASH_STOPPED = "$packageName.intent.action.CLASH_STOPPED"
    val ACTION_CLASH_REQUEST_STOP = "$packageName.intent.action.CLASH_REQUEST_STOP"
    val ACTION_PROFILE_CHANGED = "$packageName.intent.action.PROFILE_CHANGED"
    val ACTION_PROFILE_UPDATE_COMPLETED = "$packageName.intent.action.PROFILE_UPDATE_COMPLETED"
    val ACTION_PROFILE_UPDATE_FAILED = "$packageName.intent.action.PROFILE_UPDATE_FAILED"
    val ACTION_PROFILE_REQUEST_UPDATE = "$packageName.intent.action.REQUEST_UPDATE"
    val ACTION_PROFILE_SCHEDULE_UPDATES = "$packageName.intent.action.SCHEDULE_UPDATES"
    val ACTION_PROFILE_LOADED = "$packageName.intent.action.PROFILE_LOADED"
    val ACTION_OVERRIDE_CHANGED = "$packageName.intent.action.OVERRIDE_CHANGED"
    val ACTION_PROXY_GROUP_REFRESH = "$packageName.intent.action.PROXY_GROUP_REFRESH"
    /** 由 :background 进程发往主进程，写入 [com.github.kr328.clash.common.log.DebugLog] 供调试界面展示。 */
    val ACTION_DEBUG_UI_LOG = "$packageName.intent.action.DEBUG_UI_LOG"

    const val EXTRA_STOP_REASON = "stop_reason"
    const val EXTRA_UUID = "uuid"
    const val EXTRA_FAIL_REASON = "fail_reason"
    const val EXTRA_AUTO_COMMIT = "auto_commit"
    const val EXTRA_DEBUG_LOG_TAG = "debug_log_tag"
    const val EXTRA_DEBUG_LOG_MESSAGE = "debug_log_message"
}