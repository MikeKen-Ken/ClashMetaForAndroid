package com.github.kr328.clash.service.connectivitysync

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.CancellationException
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

    internal fun isConfigured(settings: ConnectivitySyncSettings): Boolean =
        hasCredentials(settings) && settings.url.trim().startsWith("https://", ignoreCase = true)

    internal fun hasCredentials(settings: ConnectivitySyncSettings): Boolean =
        settings.url.isNotBlank() && settings.username.isNotBlank() && settings.password.isNotBlank()

    /** Unix milliseconds of the last successful merge, 0 if this device never merged. */
    fun lastSyncAt(context: Context): Long = loadState(context).lastSyncAt

    fun isDue(context: Context, intervalHours: Int): Boolean {
        val state = loadState(context)
        val intervalMillis = intervalHours.coerceAtLeast(1) * 60L * 60L * 1000L
        // lastSyncAt is persisted on disk so stopping the VPN does not reset the clock.
        return System.currentTimeMillis() - state.lastSyncAt >= intervalMillis
    }

    internal suspend fun merge(
        context: Context,
        settings: ConnectivitySyncSettings,
    ): ConnectivitySyncResult = mutex.withLock {
        ConnectivitySyncStatus.begin(context)
        try {
            mergeLocked(context, settings).also {
                ConnectivitySyncStatus.succeed(context, it.pulled, it.skipped)
            }
        } catch (error: Throwable) {
            val message = if (error is CancellationException) {
                "Cancelled"
            } else {
                error.message ?: error.javaClass.simpleName
            }
            ConnectivitySyncStatus.fail(context, message)
            throw error
        }
    }

    private suspend fun mergeLocked(
        context: Context,
        settings: ConnectivitySyncSettings,
    ): ConnectivitySyncResult = withContext(Dispatchers.IO) {
        val webDav = ConnectivityStatsWebDav(settings)
        check(webDav.isConfigured()) { "Configure WebDAV before syncing connectivity statistics" }
        webDav.prepareCollections()
        ConnectivitySyncStatus.progress(context, 10)

        val state = loadState(context)
        val now = System.currentTimeMillis()
        val listed = webDav.listSnapshotRefs()
        check(!ConnectivityStatsWebDav.tooManyDevices(listed, state.deviceId)) {
            "Too many connectivity sync devices"
        }
        ConnectivitySyncStatus.progress(context, 20)

        val snapshots = linkedMapOf<String, DeviceSnapshot>()
        val skipped = mutableListOf<String>()
        val devices = listed.groupBy { it.deviceId }.entries.toList()
        devices.forEachIndexed deviceLoop@ { index, (deviceId, refs) ->
            ConnectivitySyncStatus.progress(context, 20 + 50 * index / devices.size)
            var skipReason: String? = null
            val candidates = refs.map { ref ->
                val decoded = runCatching {
                    json.decodeFromString(
                        DeviceSnapshot.serializer(),
                        webDav.download(ref).decodeToString(),
                    )
                }.onFailure {
                    skipReason = "unreadable snapshot: ${it.message ?: it.javaClass.simpleName}"
                }.getOrNull()
                decoded?.takeIf { ConnectivityStatsProtocol.snapshotMatches(it, ref) }.also {
                    if (decoded != null && it == null) skipReason = "invalid snapshot"
                }
            }
            // Slot filenames do not expose their revision. If any listed slot cannot be
            // validated, accepting the other one could resurrect a pre-reset snapshot.
            val newest = candidates.takeIf { all -> all.none { it == null } }
                ?.filterNotNull()
                ?.maxByOrNull { it.revision }
            if (newest == null) {
                val label = if (deviceId == state.deviceId) {
                    "This device"
                } else {
                    deviceLabel("", deviceId)
                }
                val reason = skipReason ?: "no complete snapshot"
                Log.w("Connectivity merge skipped $label: $reason")
                skipped += "$label: $reason"
                return@deviceLoop
            }
            snapshots[deviceId] = newest.copy(
                data = ConnectivityStatsMerge.prune(newest.data),
                resets = ConnectivityStatsProtocol.sanitizeResets(newest.resets),
            )
        }
        val activeResets = ConnectivityStatsProtocol.mergeResets(
            listOf(state.resets) + snapshots.values.map { it.resets },
        )
        val activeClearAll = ConnectivityStatsProtocol.mergeClearAll(
            listOf(state.clearAll) + snapshots.values.map { it.clearAll },
        )
        val remoteOthers = ConnectivityStatsMerge.sum(
            snapshots.filterKeys { it != state.deviceId }.values.map { snapshot ->
                ConnectivityStatsProtocol.filterSnapshotData(
                    snapshot,
                    activeResets,
                    activeClearAll,
                )
            },
        )
        val pulled = snapshots.filterKeys { it != state.deviceId }.values
            .map {
                ConnectivityPulledDevice(
                    device = deviceLabel(it.deviceName, it.deviceId),
                    updatedAt = it.updatedAt,
                    proxyCount = it.data.size,
                )
            }
            .sortedByDescending { it.updatedAt }
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
            ResetWatermarksPayload(resets = activeResets, clearAll = activeClearAll),
        )
        val localResult = json.decodeFromString(
            CoreConnectivityMergeResult.serializer(),
            Clash.mergeProxyConnectivityStats(
                previousOthersPayload,
                remoteOthersPayload,
                resetWatermarksPayload,
            ),
        )
        check(localResult.ok) {
            localResult.error ?: "Core rejected merged connectivity statistics"
        }
        ConnectivitySyncStatus.progress(context, 80)
        val mergedResets = ConnectivityStatsProtocol.mergeResets(
            listOf(activeResets, localResult.resets),
        )
        val mergedClearAll = ConnectivityStatsProtocol.mergeClearAll(
            listOf(activeClearAll, localResult.clearAll),
        )
        // Preserve adopted reset knowledge even when the following upload fails. This does
        // not advance revision, baseline, or lastSyncAt, so the merge is still not successful.
        saveState(
            context,
            state.copy(v = PROTOCOL_VERSION, resets = mergedResets, clearAll = mergedClearAll),
        )
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
            clearAll = mergedClearAll,
            deviceName = "Android ${Build.MODEL}",
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
                clearAll = mergedClearAll,
                lastSyncAt = now,
            ),
        )
        ConnectivitySyncBackoff.clear()
        ConnectivitySyncResult(
            deviceCount = (snapshots.keys + state.deviceId).size,
            proxyCount = localResult.merged.size,
            lastSyncAt = now,
            pulled = pulled,
            skipped = skipped,
        )
    }

    private fun deviceLabel(name: String, deviceId: String): String =
        name.trim().ifEmpty { "Device ${deviceId.take(6)}" }

    suspend fun reset(
        context: Context,
        proxyNames: Collection<String>,
        includeKnownResets: Boolean = false,
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
                ResetWatermarksPayload(
                    resets = resets.filterKeys { it in names },
                    clearAll = state.clearAll,
                ),
            )
            saveState(
                context,
                state.copy(
                    v = PROTOCOL_VERSION,
                    lastOthers = state.lastOthers - names.toSet(),
                    resets = resets,
                ),
            )
            names.forEach { name ->
                check(Clash.clearProxyConnectivityStatsFor(name, resetPayload)) {
                    "Core failed to persist connectivity reset"
                }
            }
        }
    }

    suspend fun clearAll(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val state = loadState(context)
            val clearAll = ConnectivityStatsProtocol.advanceClearAll(state.clearAll, state.deviceId)
            val resetPayload = json.encodeToString(
                ResetWatermarksPayload.serializer(),
                ResetWatermarksPayload(resets = state.resets, clearAll = clearAll),
            )
            saveState(
                context,
                state.copy(
                    v = PROTOCOL_VERSION,
                    lastOthers = emptyMap(),
                    clearAll = clearAll,
                ),
            )
            check(Clash.clearProxyConnectivityStats(resetPayload)) {
                "Core failed to persist connectivity reset"
            }
        }
    }

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
                clearAll = ConnectivityStatsProtocol.sanitizeClearAll(loaded.clearAll),
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
