package com.github.kr328.clash.connectivitysync

import android.content.Context
import android.util.AtomicFile
import com.github.kr328.clash.design.store.UiStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

object ConnectivityStatsSync {
    const val DEFAULT_INTERVAL_HOURS = 24
    val intervalOptions = arrayOf(1, 6, 12, 24, 48, 168)

    private const val PROTOCOL_VERSION = 1
    private const val STORE_VERSION = 2
    private const val STATE_FILE = "connectivity-sync-state.json"
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun isConfigured(store: UiStore): Boolean = hasCredentials(store) &&
        store.webdavUrl.trim().startsWith("https://", ignoreCase = true)

    fun hasCredentials(store: UiStore): Boolean = store.webdavUrl.isNotBlank() &&
        store.webdavUsername.isNotBlank() && store.webdavPassword.isNotBlank()

    suspend fun isDue(context: Context, intervalHours: Int): Boolean = withContext(Dispatchers.IO) {
        val state = loadState(context)
        val intervalMillis = intervalHours.coerceAtLeast(1) * 60L * 60L * 1000L
        System.currentTimeMillis() - state.lastSyncAt >= intervalMillis
    }

    suspend fun merge(
        context: Context,
        store: UiStore,
        mergeLocal: suspend (previousOthers: String, remoteOthers: String) -> String,
    ): ConnectivitySyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val webDav = ConnectivityStatsWebDav(store)
            check(webDav.isConfigured()) { "Configure WebDAV before syncing connectivity statistics" }
            webDav.prepareCollections()

            val state = loadState(context)
            val now = System.currentTimeMillis()
            val listed = webDav.listDeviceIds()
            check(!ConnectivityStatsWebDav.tooManyDevices(listed, state.deviceId)) {
                "Too many connectivity sync devices"
            }

            val snapshots = linkedMapOf<String, StatsData>()
            listed.forEach { deviceId ->
                val snapshot = runCatching {
                    json.decodeFromString(
                        DeviceSnapshot.serializer(),
                        webDav.download(deviceId).decodeToString(),
                    )
                }.getOrNull()
                if (snapshot == null ||
                    snapshot.v != PROTOCOL_VERSION ||
                    snapshot.deviceId != deviceId
                ) {
                    return@forEach
                }
                snapshots[deviceId] = ConnectivityStatsMerge.prune(snapshot.data)
            }
            val remoteOthers = ConnectivityStatsMerge.sum(
                snapshots.filterKeys { it != state.deviceId }.values,
            )
            val previousOthersPayload = json.encodeToString(
                StatsFile.serializer(),
                StatsFile(v = STORE_VERSION, data = state.lastOthers),
            )
            val remoteOthersPayload = json.encodeToString(
                StatsFile.serializer(),
                StatsFile(v = STORE_VERSION, data = remoteOthers),
            )
            val localResult = json.decodeFromString(
                CoreConnectivityMergeResult.serializer(),
                mergeLocal(previousOthersPayload, remoteOthersPayload),
            )
            check(localResult.ok) {
                localResult.error ?: "Core rejected merged connectivity statistics"
            }
            saveState(
                context,
                SyncState(
                    deviceId = state.deviceId,
                    lastOthers = remoteOthers,
                    lastSyncAt = now,
                ),
            )
            val ownSnapshot = DeviceSnapshot(
                deviceId = state.deviceId,
                updatedAt = now,
                data = localResult.own,
            )
            runCatching {
                webDav.upload(
                    state.deviceId,
                    json.encodeToString(DeviceSnapshot.serializer(), ownSnapshot)
                        .encodeToByteArray(),
                )
            }
            ConnectivitySyncResult(
                deviceCount = (snapshots.keys + state.deviceId).size,
                proxyCount = localResult.merged.size,
                lastSyncAt = now,
            )
        }
    }

    suspend fun resetBaseline(context: Context, proxyName: String? = null) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val state = loadState(context)
            val remaining = if (proxyName.isNullOrEmpty()) {
                emptyMap()
            } else {
                state.lastOthers - proxyName
            }
            saveState(context, state.copy(lastOthers = remaining))
        }
    }

    private fun loadState(context: Context): SyncState {
        val file = context.filesDir.resolve(STATE_FILE)
        val loaded = runCatching {
            json.decodeFromString(SyncState.serializer(), file.readText())
        }.getOrNull()
        if (loaded != null && loaded.v == PROTOCOL_VERSION &&
            ConnectivityStatsWebDav.isValidDeviceId(loaded.deviceId)
        ) {
            return loaded.copy(lastOthers = ConnectivityStatsMerge.prune(loaded.lastOthers))
        }
        return SyncState(deviceId = UUID.randomUUID().toString())
    }

    private fun saveState(context: Context, state: SyncState) {
        val file = context.filesDir.resolve(STATE_FILE)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(SyncState.serializer(), state).encodeToByteArray())
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}
