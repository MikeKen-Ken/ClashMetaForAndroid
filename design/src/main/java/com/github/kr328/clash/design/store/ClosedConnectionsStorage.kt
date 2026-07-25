package com.github.kr328.clash.design.store

import android.content.Context
import com.github.kr328.clash.design.adapter.ClosedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 已关闭连接持久化（与桌面端一致）：
 * 仅按条数上限保留 closedAt 最新的记录，无时间限制。
 * 调用方应对写盘做节流，避免连接快照高频刷新时整表反复写入。
 */
object ClosedConnectionsStorage {
    private const val FILE_NAME = "closed_connections.json"

    /** 默认条数上限，与桌面端 DEFAULT_CLOSED_CONNECTIONS_LIMIT 一致 */
    const val DEFAULT_MAX_CLOSED_COUNT = 5000

    /** 可选上限中的最大值，用于无设置时的硬顶 */
    const val ABSOLUTE_MAX_CLOSED_COUNT = 20000

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(ClosedEntry.serializer())

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun trimToMaxCount(
        entries: List<ClosedEntry>,
        maxCount: Int = DEFAULT_MAX_CLOSED_COUNT,
    ): List<ClosedEntry> {
        val cap = maxCount.coerceIn(1, ABSOLUTE_MAX_CLOSED_COUNT)
        if (entries.size <= cap) return entries
        return entries.sortedByDescending { it.closedAt }.take(cap)
    }

    suspend fun load(context: Context, maxCount: Int = DEFAULT_MAX_CLOSED_COUNT): List<ClosedEntry> =
        withContext(Dispatchers.IO) {
            try {
                val f = file(context)
                if (!f.exists()) return@withContext emptyList()
                val text = f.readText()
                if (text.isBlank()) return@withContext emptyList()
                val loaded = json.decodeFromString(listSerializer, text)
                trimToMaxCount(loaded, maxCount)
            } catch (_: Throwable) {
                emptyList()
            }
        }

    suspend fun save(
        context: Context,
        entries: List<ClosedEntry>,
        maxCount: Int = DEFAULT_MAX_CLOSED_COUNT,
    ) = withContext(Dispatchers.IO) {
        try {
            val trimmed = trimToMaxCount(entries, maxCount)
            val f = file(context)
            if (trimmed.isEmpty()) {
                if (f.exists()) f.delete()
                return@withContext
            }
            f.writeText(json.encodeToString(listSerializer, trimmed))
        } catch (_: Throwable) { }
    }
}
