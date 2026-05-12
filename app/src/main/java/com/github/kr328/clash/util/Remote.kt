package com.github.kr328.clash.util

import android.os.DeadObjectException
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.DebugLog
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

private val clashPatchMutex = Mutex()

private const val DBG_TAG_UI_OVERRIDE = "覆写UI"

suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IClashManager.() -> T
): T {
    while (true) {
        val remote = Remote.service.remote.get()
        val client = remote.clash()

        try {
            return withContext(context) { client.block() }
        } catch (e: DeadObjectException) {
            Log.w("Remote services panic")

            Remote.service.remote.reset(remote)
        }
    }
}

/**
 * 在应用级 [Global] 作用域执行关键挂起逻辑，避免 Activity [kotlinx.coroutines.MainScope]
 * 因 recreate/onDestroy 取消后，日志级别、覆写 patch 等写入未完成。
 */
fun scheduleCriticalWork(logTag: String, block: suspend () -> Unit) {
    Global.launch {
        withContext(NonCancellable) {
            try {
                block()
            } catch (e: Exception) {
                Log.e("$logTag: ${e.message}", e)
                DebugLog.e(DBG_TAG_UI_OVERRIDE, "$logTag 失败: ${e.message}")
            }
        }
    }
}

/** 在 [scheduleCriticalWork] 中调用 [withClash] 完成远程覆写或同类变更。 */
fun scheduleClashMutation(logTag: String, block: suspend IClashManager.() -> Unit) {
    scheduleCriticalWork(logTag) {
        DebugLog.i(DBG_TAG_UI_OVERRIDE, "开始 $logTag")
        clashPatchMutex.withLock {
            withClash(block = block)
        }
        DebugLog.i(DBG_TAG_UI_OVERRIDE, "完成 $logTag")
    }
}

suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IProfileManager.() -> T
): T {
    while (true) {
        val remote = Remote.service.remote.get()
        val client = remote.profile()

        try {
            return withContext(context) { client.block() }
        } catch (e: DeadObjectException) {
            Log.w("Remote services panic")

            Remote.service.remote.reset(remote)
        }
    }
}
