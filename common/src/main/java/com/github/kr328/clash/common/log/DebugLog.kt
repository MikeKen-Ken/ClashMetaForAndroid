package com.github.kr328.clash.common.log

import android.util.Log
import java.util.Collections

/**
 * In-memory debug log for the in-app debug viewer.
 * Call d/i/w/e with a tag and message; entries are kept in a ring buffer
 * and can be shown in the Debug screen. Also forwards to android.util.Log.
 */
object DebugLog {
    const val LEVEL_VERBOSE = 2
    const val LEVEL_DEBUG = 3
    const val LEVEL_INFO = 4
    const val LEVEL_WARN = 5
    const val LEVEL_ERROR = 6

    data class Entry(
        val tag: String,
        val level: Int,
        val message: String,
        val timeMillis: Long,
    )

    private const val MAX_ENTRIES = 1000
    private val buffer = Collections.synchronizedList(mutableListOf<Entry>())

    fun v(tag: String, message: String) = append(LEVEL_VERBOSE, tag, message)
    fun d(tag: String, message: String) = append(LEVEL_DEBUG, tag, message)
    fun i(tag: String, message: String) = append(LEVEL_INFO, tag, message)
    fun w(tag: String, message: String) = append(LEVEL_WARN, tag, message)
    fun e(tag: String, message: String) = append(LEVEL_ERROR, tag, message)

    private fun append(level: Int, tag: String, message: String) {
        when (level) {
            LEVEL_VERBOSE -> Log.v(tag, message)
            LEVEL_DEBUG -> Log.d(tag, message)
            LEVEL_INFO -> Log.i(tag, message)
            LEVEL_WARN -> Log.w(tag, message)
            LEVEL_ERROR -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }
        synchronized(buffer) {
            while (buffer.size >= MAX_ENTRIES) buffer.removeAt(0)
            buffer.add(Entry(tag, level, message, System.currentTimeMillis()))
        }
    }

    /** Snapshot of current entries (newest at end). */
    fun getEntries(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }
}
