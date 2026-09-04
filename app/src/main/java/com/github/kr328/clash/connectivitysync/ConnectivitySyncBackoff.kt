package com.github.kr328.clash.connectivitysync

import com.github.kr328.clash.design.store.UiStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory auto-merge failure backoff. Not persisted; process death starts at 1 minute again.
 */
internal object ConnectivitySyncBackoff {
    private val delayMs = longArrayOf(
        1L * 60L * 1000L,
        2L * 60L * 1000L,
        5L * 60L * 1000L,
        10L * 60L * 1000L,
    )

    private val failureCount = AtomicInteger(0)
    private val retryNotBeforeMs = AtomicLong(0)
    private val settingsKey = AtomicReference("")

    fun delayAfterFailures(failures: Int): Long {
        if (failures <= 0) return 0L
        val index = (failures - 1).coerceAtMost(delayMs.lastIndex)
        return delayMs[index]
    }

    fun rememberSettings(store: UiStore) {
        val key = listOf(
            store.webdavUrl,
            store.webdavUsername,
            store.webdavPassword,
            store.connectivitySyncIntervalHours.toString(),
        ).joinToString("\n")
        val previous = settingsKey.get()
        if (previous != key) {
            settingsKey.set(key)
            clear()
        }
    }

    fun clear() {
        failureCount.set(0)
        retryNotBeforeMs.set(0)
    }

    fun isOpen(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= retryNotBeforeMs.get()

    fun noteFailure(nowMs: Long = System.currentTimeMillis()) {
        val next = failureCount.incrementAndGet()
        retryNotBeforeMs.set(nowMs + delayAfterFailures(next))
    }
}
