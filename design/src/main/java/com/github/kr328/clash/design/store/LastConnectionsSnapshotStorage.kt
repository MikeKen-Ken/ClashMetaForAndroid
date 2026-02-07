package com.github.kr328.clash.design.store

import android.content.Context
import com.github.kr328.clash.core.model.ConnectionsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 上次连接快照持久化：用于在连接界面被重建（如切换模式后返回）时，
 * 仍能根据「上一份快照」与「当前快照」的差值，把因模式切换被关闭的连接记入「已关闭」列表。
 */
object LastConnectionsSnapshotStorage {
    private const val FILE_NAME = "last_connections_snapshot.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    suspend fun load(context: Context): ConnectionsSnapshot? = withContext(Dispatchers.IO) {
        try {
            val f = file(context)
            if (!f.exists()) return@withContext null
            val text = f.readText()
            if (text.isBlank()) return@withContext null
            json.decodeFromString(ConnectionsSnapshot.serializer(), text)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun save(context: Context, snapshot: ConnectionsSnapshot) = withContext(Dispatchers.IO) {
        try {
            val f = file(context)
            f.writeText(json.encodeToString(ConnectionsSnapshot.serializer(), snapshot))
        } catch (_: Throwable) { }
    }
}
