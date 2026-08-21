package com.github.kr328.clash.util

import android.os.DeadObjectException
import android.os.RemoteException
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.DebugLog
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.IRemoteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

private val clashPatchMutex = Mutex()

private const val DBG_TAG_UI_OVERRIDE = "覆写UI"
private const val REMOTE_BIND_TIMEOUT_MS = 15_000L
private const val REMOTE_RETRY_LIMIT = 5

suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IClashManager.() -> T
): T {
    return withRemoteRetry { remote ->
        withContext(context) { remote.clash().block() }
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
                DebugLog.e(DBG_TAG_UI_OVERRIDE, "$logTag failed: ${e.message}")
            }
        }
    }
}

/** 在 [scheduleCriticalWork] 中调用 [withClash] 完成远程覆写或同类变更。 */
fun scheduleClashMutation(logTag: String, block: suspend IClashManager.() -> Unit) {
    scheduleCriticalWork(logTag) {
        DebugLog.i(DBG_TAG_UI_OVERRIDE, "Starting $logTag")
        clashPatchMutex.withLock {
            withClash(block = block)
        }
        DebugLog.i(DBG_TAG_UI_OVERRIDE, "Completed $logTag")
    }
}

suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IProfileManager.() -> T
): T {
    return withRemoteRetry { remote ->
        withContext(context) { remote.profile().block() }
    }
}

private suspend fun <T> withRemoteRetry(block: suspend (IRemoteService) -> T): T {
    var attempt = 0
    while (true) {
        attempt++
        val remote = try {
            withTimeout(REMOTE_BIND_TIMEOUT_MS) {
                Remote.service.remote.get()
            }
        } catch (e: TimeoutCancellationException) {
            throw RemoteException("Clash service bind timeout after ${REMOTE_BIND_TIMEOUT_MS}ms")
        }

        try {
            return block(remote)
        } catch (e: DeadObjectException) {
            Log.w("Remote services panic (attempt $attempt/$REMOTE_RETRY_LIMIT)")
            Remote.service.remote.reset(remote)
            if (attempt >= REMOTE_RETRY_LIMIT) {
                throw RemoteException("Clash service unavailable after $REMOTE_RETRY_LIMIT retries").initCause(e)
            }
            delay(200L * attempt)
        }
    }
}
