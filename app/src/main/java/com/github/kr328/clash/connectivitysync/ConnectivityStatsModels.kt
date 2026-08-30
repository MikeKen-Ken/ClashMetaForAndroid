package com.github.kr328.clash.connectivitysync

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
)

internal typealias StatsData = Map<String, ProxyEntry>

@Serializable
internal data class StatsFile(
    val v: Int = 2,
    val data: StatsData = emptyMap(),
)

@Serializable
internal data class DeviceSnapshot(
    val v: Int = 1,
    val deviceId: String,
    val updatedAt: Long,
    val data: StatsData = emptyMap(),
)

@Serializable
internal data class SyncState(
    val v: Int = 1,
    val deviceId: String,
    val lastOthers: StatsData = emptyMap(),
    val lastSyncAt: Long = 0,
)

@Serializable
internal data class CoreConnectivityMergeResult(
    val ok: Boolean,
    val error: String? = null,
    val own: StatsData = emptyMap(),
    val merged: StatsData = emptyMap(),
)

data class ConnectivitySyncResult(
    val deviceCount: Int,
    val proxyCount: Int,
    val lastSyncAt: Long,
)
