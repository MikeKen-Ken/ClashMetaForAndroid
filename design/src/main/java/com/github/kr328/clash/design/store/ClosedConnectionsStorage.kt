package com.github.kr328.clash.design.store

import android.content.Context
import com.github.kr328.clash.design.adapter.ClosedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 已关闭连接持久化：写入应用私有文件，重新打开链接界面时恢复「已关闭」列表，避免被清空。
 */
object ClosedConnectionsStorage {
    private const val FILE_NAME = "closed_connections.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    suspend fun load(context: Context): List<ClosedEntry> = withContext(Dispatchers.IO) {
        try {
            val f = file(context)
            if (!f.exists()) return@withContext emptyList()
            val text = f.readText()
            if (text.isBlank()) return@withContext emptyList()
            json.decodeFromString(ListSerializer(ClosedEntry.serializer()), text)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun save(context: Context, entries: List<ClosedEntry>) = withContext(Dispatchers.IO) {
        try {
            val f = file(context)
            if (entries.isEmpty()) {
                if (f.exists()) f.delete()
                return@withContext
            }
            f.writeText(json.encodeToString(ListSerializer(ClosedEntry.serializer()), entries))
        } catch (_: Throwable) { }
    }
}
