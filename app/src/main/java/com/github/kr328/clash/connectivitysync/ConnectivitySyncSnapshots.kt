package com.github.kr328.clash.connectivitysync

import android.content.Context
import com.github.kr328.clash.design.model.ConnectivityMergeStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reads the files the VPN process writes. Safe while that process is not running. */
object ConnectivitySyncSnapshots {
    private const val STATE_FILE = "connectivity-sync-state.json"
    private const val STATUS_FILE = "connectivity-sync-status.json"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SyncClock(val lastSyncAt: Long = 0)

    fun lastSyncAt(context: Context): Long = runCatching {
        json.decodeFromString(
            SyncClock.serializer(),
            context.filesDir.resolve(STATE_FILE).readText(),
        ).lastSyncAt
    }.getOrDefault(0L)

    fun mergeStatus(context: Context): ConnectivityMergeStatus = runCatching {
        json.decodeFromString(
            ConnectivityMergeStatus.serializer(),
            context.filesDir.resolve(STATUS_FILE).readText(),
        )
    }.getOrDefault(ConnectivityMergeStatus())
}
