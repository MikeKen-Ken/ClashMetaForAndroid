package com.github.kr328.clash.service.clash.module

/** Delay preference changes, but never delay departure from an unusable network. */
internal class NetworkHandoverGate<T>(private val settleMs: Long = 2_000L) {
    private var pending: T? = null
    private var since = 0L

    fun choose(current: T?, candidate: T?, currentUsable: Boolean, now: Long): T? {
        if (current == null || !currentUsable || candidate == current) {
            pending = null
            return candidate
        }
        if (candidate == null) {
            pending = null
            return current
        }
        if (pending != candidate) {
            pending = candidate
            since = now
        }
        if (now - since < settleMs) return current
        pending = null
        return candidate
    }

    fun remaining(now: Long): Long? =
        if (pending == null) null else (settleMs - (now - since)).coerceAtLeast(1L)
}
