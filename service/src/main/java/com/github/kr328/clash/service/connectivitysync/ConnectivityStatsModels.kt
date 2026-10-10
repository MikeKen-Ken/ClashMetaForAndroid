package com.github.kr328.clash.service.connectivitysync

import kotlinx.serialization.Serializable

@Serializable
internal data class DayCounts(
    val s: Long = 0,
    val f: Long = 0,
    val ds: Long = 0,
)

@Serializable
internal data class ProxyEntry(
    val days: Map<String, DayCounts> = emptyMap(),
    val ls: Long = 0,
)

internal typealias StatsData = Map<String, ProxyEntry>

@Serializable
internal data class ResetGeneration(
    val counter: Long = 0,
    val deviceId: String = "",
)

internal typealias ResetWatermarks = Map<String, ResetGeneration>

@Serializable
internal data class StatsFile(
    val v: Int = 2,
    val data: StatsData = emptyMap(),
)

@Serializable
internal data class DeviceSnapshot(
    val v: Int = 2,
    val deviceId: String,
    val revision: Long,
    val slot: Int,
    val updatedAt: Long,
    val resets: ResetWatermarks = emptyMap(),
    val generations: ResetWatermarks = emptyMap(),
    val data: StatsData = emptyMap(),
    val clearAll: ResetGeneration = ResetGeneration(),
    /** Display-only label; older snapshots omit it. */
    val deviceName: String = "",
)

@Serializable
internal data class SyncState(
    val v: Int = 2,
    val deviceId: String,
    val revision: Long = 0,
    val lastOthers: StatsData = emptyMap(),
    val resets: ResetWatermarks = emptyMap(),
    val lastSyncAt: Long = 0,
    val clearAll: ResetGeneration = ResetGeneration(),
)

@Serializable
internal data class ResetWatermarksPayload(
    val v: Int = 2,
    val resets: ResetWatermarks = emptyMap(),
    val clearAll: ResetGeneration = ResetGeneration(),
)

@Serializable
internal data class CoreConnectivityMergeResult(
    val ok: Boolean,
    val error: String? = null,
    val own: StatsData = emptyMap(),
    val merged: StatsData = emptyMap(),
    val resets: ResetWatermarks = emptyMap(),
    val clearAll: ResetGeneration = ResetGeneration(),
)

internal data class RemoteSnapshotRef(
    val deviceId: String,
    val slot: Int,
)

@Serializable
internal data class ConnectivityPulledDevice(
    val device: String,
    val updatedAt: Long,
    val proxyCount: Int,
)

@Serializable
data class ConnectivitySyncResult(
    val deviceCount: Int,
    val proxyCount: Int,
    val lastSyncAt: Long,
    val pulled: List<ConnectivityPulledDevice>,
    val skipped: List<String>,
)
