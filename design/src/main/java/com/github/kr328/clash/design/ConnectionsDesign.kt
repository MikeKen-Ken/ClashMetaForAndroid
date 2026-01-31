package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.core.model.ConnectionsSnapshot
import com.github.kr328.clash.design.adapter.ConnectionAdapter
import com.github.kr328.clash.design.adapter.ConnectionDisplayItem
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.design.util.toSpeedString
import com.github.kr328.clash.design.ui.ToastDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionsDesign(context: Context) : Design<ConnectionsDesign.Request>(context) {
    sealed class Request {
        data class CloseConnection(val connection: Connection) : Request()
    }

    private val binding = DesignConnectionsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ConnectionAdapter(
        context,
        onClose = { conn -> requests.trySend(Request.CloseConnection(conn)) },
        onCopy = { _, displayText ->
            launch {
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(
                    ClipData.newPlainText("connection", displayText)
                )
                showToast(R.string.copied, ToastDuration.Short)
            }
        }
    )

    /** When true, newest connections first (descending by start time). */
    private var sortNewestFirst = true
    private var lastSnapshot: ConnectionsSnapshot? = null
    /** id -> (upload, download) for speed calculation */
    private var previousTraffic: Map<String, Pair<Long, Long>> = emptyMap()
    private var previousTimeMs: Long = 0

    override val root: View
        get() = binding.root

    suspend fun patchConnections(snapshot: ConnectionsSnapshot) {
        lastSnapshot = snapshot
        val sorted = snapshot.connections.sortedBy { it.start }.let { list ->
            if (sortNewestFirst) list.asReversed() else list
        }
        val nowMs = System.currentTimeMillis()
        val intervalSec = if (previousTimeMs > 0) (nowMs - previousTimeMs) / 1000.0 else 1.0
        val intervalSecClamped = if (intervalSec < 0.5) 1.0 else intervalSec

        val displayItems = sorted.map { conn ->
            val prev = previousTraffic[conn.id]
            val downloadSpeedBps = if (prev != null) {
                ((conn.download - prev.second) / intervalSecClamped).toLong().coerceAtLeast(0)
            } else 0L
            val trafficLine = "↓ ${conn.download.toBytesString()} (${downloadSpeedBps.toSpeedString()})"
            ConnectionDisplayItem(conn, trafficLine)
        }

        previousTraffic = snapshot.connections.associate { it.id to (it.upload to it.download) }
        previousTimeMs = nowMs

        withContext(Dispatchers.Main) {
            adapter.patchDataSet(adapter::displayItems, displayItems, detectMove = true) { it.connection.id }
        }
    }

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.recyclerList.applyLinearAdapter(context, adapter)
        binding.sortOrderView.setOnClickListener { toggleSortOrder() }
    }

    private fun toggleSortOrder() {
        sortNewestFirst = !sortNewestFirst
        lastSnapshot?.let { snapshot -> launch { patchConnections(snapshot) } }
    }
}
