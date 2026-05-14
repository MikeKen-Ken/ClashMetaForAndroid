package com.github.kr328.clash.common.log

import android.util.Log
import com.github.kr328.clash.common.Global
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap

/**
 * In-memory debug log for the in-app debug viewer.
 * Call d/i/w/e with a tag and message; entries are kept in a ring buffer
 * and can be shown in the Debug screen. Also forwards to android.util.Log.
 *
 * Each line is also appended to [PERSIST_FILE] (under [android.content.Context.getFilesDir])
 * so that logs survive process death / crash; main and :background share the same package files dir.
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

    private const val PERSIST_FILE = "debug_ui_mirror.log"
    private const val PERSIST_MAX_BYTES = 400 * 1024

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
        val now = System.currentTimeMillis()
        synchronized(buffer) {
            while (buffer.size >= MAX_ENTRIES) buffer.removeAt(0)
            buffer.add(Entry(tag, level, message, now))
        }
        mirrorAppend(level, tag, message, now)
    }

    private fun persistFileOrNull(): File? {
        val app = try {
            Global.application
        } catch (_: Throwable) {
            return null
        }
        return File(app.filesDir, PERSIST_FILE)
    }

    private fun mirrorAppend(level: Int, tag: String, message: String, timeMillis: Long) {
        val file = persistFileOrNull() ?: return
        val safeTag = tag.replace('\t', ' ').replace('\n', ' ')
        val safeMsg = message.replace('\t', ' ').replace('\n', ' ')
        val line = "$timeMillis\t$level\t$safeTag\t$safeMsg\n"
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val ch = raf.channel
                val lock = ch.lock()
                try {
                    if (raf.length() + bytes.size > PERSIST_MAX_BYTES) {
                        ch.truncate(0L)
                        raf.seek(0)
                        val banner = "--- debug log truncated (max ${PERSIST_MAX_BYTES}B) ---\n"
                            .toByteArray(StandardCharsets.UTF_8)
                        ch.write(ByteBuffer.wrap(banner))
                    }
                    raf.seek(raf.length())
                    ch.write(ByteBuffer.wrap(bytes))
                } finally {
                    lock.release()
                }
            }
        } catch (_: Throwable) {
            // never break in-memory logging
        }
    }

    private fun parsePersistedFile(text: String): List<Entry> {
        val out = ArrayList<Entry>(64)
        for (rawLine in text.lineSequence()) {
            if (rawLine.isEmpty()) continue
            val parts = rawLine.split('\t', limit = 4)
            if (parts.size < 4) continue
            val time = parts[0].toLongOrNull() ?: continue
            val level = parts[1].toIntOrNull() ?: continue
            out.add(Entry(parts[2], level, parts[3], time))
        }
        return out
    }

    private fun readPersistedEntries(): List<Entry> {
        val file = persistFileOrNull() ?: return emptyList()
        if (!file.exists() || file.length() == 0L) return emptyList()
        try {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                val lock = ch.lock()
                try {
                    val len = raf.length().toInt().coerceAtMost(PERSIST_MAX_BYTES + 64 * 1024)
                    if (len <= 0) return emptyList()
                    val bytes = ByteArray(len)
                    raf.seek(0)
                    raf.readFully(bytes)
                    return parsePersistedFile(String(bytes, StandardCharsets.UTF_8))
                } finally {
                    lock.release()
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    private fun dedupeMerge(disk: List<Entry>, mem: List<Entry>): List<Entry> {
        if (disk.isEmpty()) return mem
        if (mem.isEmpty()) return disk
        val merged = LinkedHashMap<String, Entry>((disk.size + mem.size) * 4 / 3)
        fun key(e: Entry) = "${e.timeMillis}\t${e.tag}\t${e.message}"
        for (e in disk) merged[key(e)] = e
        for (e in mem) merged[key(e)] = e
        return merged.values.sortedBy { it.timeMillis }
    }

    /** Snapshot for the debug viewer: on-disk mirror plus in-memory ring (deduped, time-ordered). */
    fun getEntries(): List<Entry> {
        val mem = synchronized(buffer) { buffer.toList() }
        val disk = readPersistedEntries()
        return dedupeMerge(disk, mem)
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        val file = persistFileOrNull() ?: return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val lock = raf.channel.lock()
                try {
                    raf.channel.truncate(0L)
                } finally {
                    lock.release()
                }
            }
        } catch (_: Throwable) {
        }
    }
}
