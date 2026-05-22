package com.github.kr328.clash.design.store

import android.content.Context
import com.github.kr328.clash.design.adapter.ClosedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 已关闭连接持久化（与桌面端 PERSIST_RETENTION_HOURS 一致）：
 * 落盘保留 24 小时内的记录，供重启、后台恢复后仍能展示。
 */
object ClosedConnectionsStorage {
    private const val FILE_NAME = "closed_connections.json"

    /** 持久化保留时长（小时），与桌面端 use-connection-data 一致 */
    const val PERSIST_RETENTION_HOURS = 24

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(ClosedEntry.serializer())

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun filterByPersistRetention(entries: List<ClosedEntry>, nowMs: Long = System.currentTimeMillis()): List<ClosedEntry> {
        val maxAgeMs = PERSIST_RETENTION_HOURS * 3600 * 1000L
        return entries.filter { nowMs - it.closedAt <= maxAgeMs }
    }

    suspend fun load(context: Context): List<ClosedEntry> = withContext(Dispatchers.IO) {
        try {
            val f = file(context)
            if (!f.exists()) return@withContext emptyList()
            val text = f.readText()
            if (text.isBlank()) return@withContext emptyList()
            val loaded = json.decodeFromString(listSerializer, text)
            filterByPersistRetention(loaded)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun save(context: Context, entries: List<ClosedEntry>) = withContext(Dispatchers.IO) {
        try {
            val trimmed = filterByPersistRetention(entries)
            val f = file(context)
            if (trimmed.isEmpty()) {
                if (f.exists()) f.delete()
                return@withContext
            }
            f.writeText(json.encodeToString(listSerializer, trimmed))
        } catch (_: Throwable) { }
    }
}
