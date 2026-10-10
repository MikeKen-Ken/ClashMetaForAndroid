package com.github.kr328.clash.service.connectivitysync

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.util.sendBroadcastSelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ConnectivityMergeSnapshot(
    val phase: Phase = Phase.Idle,
    val progress: Int = 0,
    val finishedAt: Long = 0,
    val error: String? = null,
    val pulled: List<ConnectivityPulledDevice> = emptyList(),
    val skipped: List<String> = emptyList(),
) {
    enum class Phase { Idle, Running, Success, Partial, Failed }
}

/** Progress and outcome of the current or last merge, shared with the UI process by broadcast and file. */
internal object ConnectivitySyncStatus {
    private const val STATUS_FILE = "connectivity-sync-status.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val status = MutableStateFlow(ConnectivityMergeSnapshot())
    private val loadLock = Any()
    private var loaded = false

    fun currentJson(context: Context): String {
        ensureLoaded(context)
        return json.encodeToString(ConnectivityMergeSnapshot.serializer(), status.value)
    }

    suspend fun begin(context: Context) {
        ensureLoaded(context)
        publish(context, ConnectivityMergeSnapshot(phase = ConnectivityMergeSnapshot.Phase.Running), persist = true)
    }

    fun progress(context: Context, percent: Int) {
        val next = status.value.copy(progress = percent.coerceIn(0, 99))
        publish(context, next, persist = false)
    }

    suspend fun succeed(
        context: Context,
        pulled: List<ConnectivityPulledDevice>,
        skipped: List<String>,
    ) = finish(
        context,
        ConnectivityMergeSnapshot(
            phase = if (skipped.isEmpty()) {
                ConnectivityMergeSnapshot.Phase.Success
            } else {
                ConnectivityMergeSnapshot.Phase.Partial
            },
            progress = 100,
            finishedAt = System.currentTimeMillis(),
            pulled = pulled,
            skipped = skipped,
        ),
    )

    suspend fun fail(context: Context, error: String) = finish(
        context,
        ConnectivityMergeSnapshot(
            phase = ConnectivityMergeSnapshot.Phase.Failed,
            progress = 100,
            finishedAt = System.currentTimeMillis(),
            error = error,
        ),
    )

    private suspend fun finish(context: Context, value: ConnectivityMergeSnapshot) =
        withContext(NonCancellable + Dispatchers.IO) {
            publish(context, value, persist = true)
        }

    private fun publish(context: Context, value: ConnectivityMergeSnapshot, persist: Boolean) {
        status.value = value
        val payload = json.encodeToString(ConnectivityMergeSnapshot.serializer(), value)
        if (persist) {
            runCatching { save(context, payload) }
        }
        context.applicationContext.sendBroadcastSelf(
            Intent(Intents.ACTION_CONNECTIVITY_MERGE_STATUS)
                .putExtra(Intents.EXTRA_CONNECTIVITY_MERGE_STATUS, payload),
        )
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val stored = runCatching {
                json.decodeFromString(
                    ConnectivityMergeSnapshot.serializer(),
                    context.filesDir.resolve(STATUS_FILE).readText(),
                )
            }.getOrNull()
            val restored = when {
                stored == null -> null
                stored.phase == ConnectivityMergeSnapshot.Phase.Running -> stored.copy(
                    phase = ConnectivityMergeSnapshot.Phase.Failed,
                    progress = 100,
                    error = "Interrupted",
                )
                else -> stored
            }
            if (restored != null) {
                status.compareAndSet(ConnectivityMergeSnapshot(), restored)
                if (restored !== stored) {
                    runCatching {
                        save(
                            context,
                            json.encodeToString(ConnectivityMergeSnapshot.serializer(), restored),
                        )
                    }
                }
            }
            loaded = true
        }
    }

    private fun save(context: Context, payload: String) {
        val atomic = AtomicFile(context.filesDir.resolve(STATUS_FILE))
        val output = atomic.startWrite()
        try {
            output.write(payload.encodeToByteArray())
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}
