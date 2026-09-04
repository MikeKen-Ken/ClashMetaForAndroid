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

    private const val PROTOCOL_VERSION = ConnectivityStatsProtocol.PROTOCOL_VERSION
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
        // lastSyncAt is persisted on disk so closing the app does not reset the 24h clock.
        System.currentTimeMillis() - state.lastSyncAt >= intervalMillis
    }

    suspend fun merge(
        context: Context,
        store: UiStore,
        mergeLocal: suspend (
            previousOthers: String,
            remoteOthers: String,
            resetWatermarks: String,
        ) -> String,
    ): ConnectivitySyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val webDav = ConnectivityStatsWebDav(store)
            check(webDav.isConfigured()) { "Configure WebDAV before syncing connectivity statistics" }
            webDav.prepareCollections()

            val state = loadState(context)
            val now = System.currentTimeMillis()
            val listed = webDav.listSnapshotRefs()
            check(!ConnectivityStatsWebDav.tooManyDevices(listed, state.deviceId)) {
                "Too many connectivity sync devices"
            }

            val snapshots = linkedMapOf<String, DeviceSnapshot>()
            listed.groupBy { it.deviceId }.forEach deviceLoop@ { (deviceId, refs) ->
                val candidates = refs.map { ref ->
                    runCatching {
                        json.decodeFromString(
                            DeviceSnapshot.serializer(),
                            webDav.download(ref).decodeToString(),
                        )
                    }.getOrNull()?.takeIf {
                        ConnectivityStatsProtocol.snapshotMatches(it, ref)
                    }
                }
                // Slot filenames do not expose their revision. If any listed slot cannot be
                // validated, accepting the other one could resurrect a pre-reset snapshot.
                if (candidates.any { it == null }) return@deviceLoop
                val newest = candidates.filterNotNull().maxByOrNull { it.revision }
                    ?: return@deviceLoop
                snapshots[deviceId] = newest.copy(
                    data = ConnectivityStatsMerge.prune(newest.data),
                    resets = ConnectivityStatsProtocol.sanitizeResets(newest.resets),
                )
            }
            val activeResets = ConnectivityStatsProtocol.mergeResets(
                listOf(state.resets) + snapshots.values.map { it.resets },
            )
            val remoteOthers = ConnectivityStatsMerge.sum(
                snapshots.filterKeys { it != state.deviceId }.values.map { snapshot ->
                    ConnectivityStatsProtocol.filterSnapshotData(snapshot, activeResets)
                },
            )
            val previousOthersPayload = json.encodeToString(
                StatsFile.serializer(),
                StatsFile(v = STORE_VERSION, data = state.lastOthers),
            )
            val remoteOthersPayload = json.encodeToString(
                StatsFile.serializer(),
                StatsFile(v = STORE_VERSION, data = remoteOthers),
            )
            val resetWatermarksPayload = json.encodeToString(
                ResetWatermarksPayload.serializer(),
                ResetWatermarksPayload(resets = activeResets),
            )
            val localResult = json.decodeFromString(
                CoreConnectivityMergeResult.serializer(),
                mergeLocal(previousOthersPayload, remoteOthersPayload, resetWatermarksPayload),
            )
            check(localResult.ok) {
                localResult.error ?: "Core rejected merged connectivity statistics"
            }
            val mergedResets = ConnectivityStatsProtocol.mergeResets(
                listOf(activeResets, localResult.resets),
            )
            // Preserve adopted reset knowledge even when the following upload fails. This does
            // not advance revision, baseline, or lastSyncAt, so the merge is still not successful.
            saveState(context, state.copy(v = PROTOCOL_VERSION, resets = mergedResets))
            val remoteOwnRevision = snapshots[state.deviceId]?.revision ?: 0
            val currentRevision = maxOf(state.revision, remoteOwnRevision)
            check(currentRevision < ConnectivityStatsProtocol.MAX_SAFE_COUNTER) {
                "Connectivity snapshot revision exhausted"
            }
            val nextRevision = currentRevision + 1
            val slot = (nextRevision % ConnectivityStatsProtocol.SLOT_COUNT).toInt()
            val ownSnapshot = DeviceSnapshot(
                deviceId = state.deviceId,
                revision = nextRevision,
                slot = slot,
                updatedAt = now,
                resets = mergedResets,
                generations = ConnectivityStatsProtocol.generationsFor(localResult.own, mergedResets),
                data = localResult.own,
            )
            webDav.upload(
                RemoteSnapshotRef(state.deviceId, slot),
                json.encodeToString(DeviceSnapshot.serializer(), ownSnapshot).encodeToByteArray(),
            )
            saveState(
                context,
                SyncState(
                    deviceId = state.deviceId,
                    revision = nextRevision,
                    lastOthers = remoteOthers,
                    resets = mergedResets,
                    lastSyncAt = now,
                ),
            )
            ConnectivitySyncBackoff.clear()
            ConnectivitySyncResult(
                deviceCount = (snapshots.keys + state.deviceId).size,
                proxyCount = localResult.merged.size,
                lastSyncAt = now,
            )
        }
    }

    suspend fun reset(
        context: Context,
        proxyNames: Collection<String>,
        includeKnownResets: Boolean = false,
        clearLocal: suspend (resetWatermarks: String) -> Boolean,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val state = loadState(context)
            val requestedNames = proxyNames.asSequence().filter { it.isNotEmpty() }
            val names = if (includeKnownResets) {
                (requestedNames + state.resets.keys.asSequence()).distinct().toList()
            } else {
                requestedNames.distinct().toList()
            }
            if (names.isEmpty()) return@withContext
            val resets = ConnectivityStatsProtocol.advanceResets(
                state.resets,
                names,
                state.deviceId,
            )
            val resetPayload = json.encodeToString(
                ResetWatermarksPayload.serializer(),
                ResetWatermarksPayload(resets = resets.filterKeys { it in names }),
            )
            saveState(
                context,
                state.copy(
                    v = PROTOCOL_VERSION,
                    lastOthers = state.lastOthers - names.toSet(),
                    resets = resets,
                ),
            )
            check(clearLocal(resetPayload)) { "Core failed to persist connectivity reset" }
        }
    }

    internal fun namesFromStatsPayload(raw: String): Set<String> = runCatching {
        json.decodeFromString(StatsFile.serializer(), raw).data.keys
    }.getOrDefault(emptySet())

    private fun loadState(context: Context): SyncState {
        val file = context.filesDir.resolve(STATE_FILE)
        val loaded = runCatching {
            json.decodeFromString(SyncState.serializer(), file.readText())
        }.getOrNull()
        if (loaded != null && loaded.v in 1..PROTOCOL_VERSION &&
            ConnectivityStatsWebDav.isValidDeviceId(loaded.deviceId)
        ) {
            return loaded.copy(
                v = PROTOCOL_VERSION,
                lastOthers = ConnectivityStatsMerge.prune(loaded.lastOthers),
                resets = ConnectivityStatsProtocol.sanitizeResets(loaded.resets),
            )
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
