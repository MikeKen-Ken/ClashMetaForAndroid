package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.core.model.ConnectionsSnapshot
import com.github.kr328.clash.design.adapter.ClosedEntry
import com.github.kr328.clash.design.adapter.ConnectionAdapter
import com.github.kr328.clash.design.adapter.ConnectionDisplayItem
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.store.LastConnectionsSnapshotStorage
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.formatConnectionStartTime
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.design.util.toSpeedString
import com.github.kr328.clash.design.ui.ToastDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RETENTION_HOURS_OPTIONS = listOf(1, 3, 8, 24)

class ConnectionsDesign(
    context: Context,
    private val uiStore: UiStore,
) : Design<ConnectionsDesign.Request>(context) {
    sealed class Request {
        data class CloseConnection(val connection: Connection) : Request()
        object ClearClosedConnections : Request()
        object CloseAllConnections : Request()
        object CloseConnectionsExcludingDirect : Request()
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

    /** 当前会话的已关闭连接（带时间戳、按保留时长裁剪），不落盘，仅内存 */
    private val closedEntries = mutableListOf<ClosedEntry>()
    var showActiveTab: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            launch { refreshDisplayItems() }
        }
    var mergeByDomain: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!showActiveTab) launch { refreshDisplayItems() }
        }

    val retentionHoursOptions: List<Int> get() = RETENTION_HOURS_OPTIONS
    var retentionHours: Int
        get() {
            val v = uiStore.connectionsClosedRetentionHours
            return if (v in RETENTION_HOURS_OPTIONS) v else 8
        }
        set(value) {
            if (value !in RETENTION_HOURS_OPTIONS) return
            uiStore.connectionsClosedRetentionHours = value
            if (!showActiveTab) launch { refreshDisplayItems() }
        }

    val activeCount: Int get() = lastSnapshot?.connections?.size ?: 0
    val closedCount: Int get() = closedEntries.size

    override val root: View
        get() = binding.root

    suspend fun patchConnections(snapshot: ConnectionsSnapshot) {
        var previous = lastSnapshot
        val nowMs = System.currentTimeMillis()

        if (previous == null) {
            previous = LastConnectionsSnapshotStorage.load(context)
        }

        if (previous != null) {
            val previousIds = previous.connections.map { it.id }.toSet()
            val currentIds = snapshot.connections.map { it.id }.toSet()
            previous.connections
                .filter { it.id !in currentIds }
                .forEach { conn -> closedEntries.add(ClosedEntry(conn, nowMs)) }
        }

        val retentionMs = retentionHours * 3600 * 1000L
        closedEntries.removeAll { nowMs - it.closedAt > retentionMs }
        // 已关闭列表不再每次 patch 写盘，改为在离开连接页时由 Activity.onStop 统一持久化，避免每秒一次大 JSON 写入

        lastSnapshot = snapshot
        // 不在每次 patch 时写盘，避免每秒一次大 JSON 写入；仅在离开连接页时由 Activity.onStop 调用 persistLastSnapshot()
        val intervalSec = if (previousTimeMs > 0) (nowMs - previousTimeMs) / 1000.0 else 1.0
        val intervalSecClamped = if (intervalSec < 0.5) 1.0 else intervalSec

        val sorted = snapshot.connections.sortedBy { it.start }.let { list ->
            if (sortNewestFirst) list.asReversed() else list
        }
        val displayItems = sorted.map { conn ->
            val prev = previousTraffic[conn.id]
            val downloadSpeedBps = if (prev != null) {
                ((conn.download - prev.second) / intervalSecClamped).toLong().coerceAtLeast(0)
            } else 0L
            val trafficLine = "↓ ${conn.download.toBytesString()} (${downloadSpeedBps.toSpeedString()})"
            ConnectionDisplayItem(conn, trafficLine, conn.start.formatConnectionStartTime(), isClosed = false)
        }

        previousTraffic = snapshot.connections.associate { it.id to (it.upload to it.download) }
        previousTimeMs = nowMs

        withContext(Dispatchers.Main) {
            if (showActiveTab) {
                adapter.patchDataSet(adapter::displayItems, displayItems, detectMove = true) { it.connection.id }
            } else {
                refreshDisplayItems()
            }
        }
    }

    private suspend fun refreshDisplayItems() {
        withContext(Dispatchers.Main) {
            val items = if (showActiveTab) buildActiveDisplayItems() else buildClosedDisplayItems()
            adapter.patchDataSet(adapter::displayItems, items, detectMove = true) { it.connection.id }
        }
    }

    private fun buildActiveDisplayItems(): List<ConnectionDisplayItem> {
        val snapshot = lastSnapshot ?: return emptyList()
        val nowMs = System.currentTimeMillis()
        val intervalSec = if (previousTimeMs > 0) (nowMs - previousTimeMs) / 1000.0 else 1.0
        val intervalSecClamped = if (intervalSec < 0.5) 1.0 else intervalSec
        val sorted = snapshot.connections.sortedBy { it.start }.let { list ->
            if (sortNewestFirst) list.asReversed() else list
        }
        return sorted.map { conn ->
            val prev = previousTraffic[conn.id]
            val downloadSpeedBps = if (prev != null) {
                ((conn.download - prev.second) / intervalSecClamped).toLong().coerceAtLeast(0)
            } else 0L
            val trafficLine = "↓ ${conn.download.toBytesString()} (${downloadSpeedBps.toSpeedString()})"
            ConnectionDisplayItem(conn, trafficLine, conn.start.formatConnectionStartTime(), isClosed = false)
        }
    }

    private fun buildClosedDisplayItems(): List<ConnectionDisplayItem> {
        val retentionMs = retentionHours * 3600 * 1000L
        val nowMs = System.currentTimeMillis()
        var conns = closedEntries
            .filter { nowMs - it.closedAt <= retentionMs }
            .map { it.connection }
        if (mergeByDomain && conns.isNotEmpty()) {
            conns = mergeConnectionsByHost(conns)
        }
        val sorted = conns.sortedBy { it.start }.let { list ->
            if (sortNewestFirst) list.asReversed() else list
        }
        return sorted.map { conn ->
            val trafficLine = "↓ ${conn.download.toBytesString()} ↑ ${conn.upload.toBytesString()}"
            ConnectionDisplayItem(conn, trafficLine, conn.start.formatConnectionStartTime(), isClosed = true)
        }
    }

    private fun mergeConnectionsByHost(connections: List<Connection>): List<Connection> {
        val byHost = connections.groupBy { it.metadata?.host?.takeIf { h -> h.isNotEmpty() } ?: it.id }
        return byHost.values.map { group ->
            val first = group.first()
            val upload = group.sumOf { it.upload }
            val download = group.sumOf { it.download }
            val latest = group.maxByOrNull { it.start } ?: first
            first.copy(upload = upload, download = download, start = latest.start)
        }
    }

    fun clearClosedConnections() {
        closedEntries.clear()
        if (!showActiveTab) launch { refreshDisplayItems() }
    }

    fun onTabActiveClick() {
        showActiveTab = true
        updateTabAndToolbarVisibility()
    }

    fun onTabClosedClick() {
        showActiveTab = false
        updateTabAndToolbarVisibility()
    }

    fun onMergeByDomainClick() {
        mergeByDomain = !mergeByDomain
        binding.mergeByDomainButton?.text = context.getString(
            if (mergeByDomain) R.string.connections_cancel_merge else R.string.connections_merge_by_domain
        )
    }

    fun onClearClosedClick() {
        requests.trySend(Request.ClearClosedConnections)
    }

    fun onCloseAllConnectionsClick() {
        requests.trySend(Request.CloseAllConnections)
    }

    fun onCloseConnectionsExcludingDirectClick() {
        requests.trySend(Request.CloseConnectionsExcludingDirect)
    }

    /** 在离开连接页时调用，仅持久化当前活跃快照（供返回时计算「因模式切换被关掉的连接」），已关闭列表不再写盘以省电。 */
    suspend fun persistState() {
        lastSnapshot?.let { LastConnectionsSnapshotStorage.save(context, it) }
    }

    private fun updateTabAndToolbarVisibility() {
        binding.tabActiveButton?.isSelected = showActiveTab
        binding.tabClosedButton?.isSelected = !showActiveTab
        binding.closedToolbar?.visibility = if (showActiveTab) View.GONE else View.VISIBLE
        binding.activeToolbar?.visibility = if (showActiveTab) View.VISIBLE else View.GONE
    }

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.recyclerList.applyLinearAdapter(context, adapter)
        binding.sortOrderView.setOnClickListener { toggleSortOrder() }
        updateTabAndToolbarVisibility()
        setupRetentionSpinner()
        binding.mergeByDomainButton?.text = context.getString(
            if (mergeByDomain) R.string.connections_cancel_merge else R.string.connections_merge_by_domain
        )
    }

    private fun setupRetentionSpinner() {
        val spinner = binding.retentionSpinner ?: return
        val labels = retentionHoursOptions.map { h ->
            context.getString(when (h) {
                1 -> R.string.connections_retention_1h
                3 -> R.string.connections_retention_3h
                8 -> R.string.connections_retention_8h
                24 -> R.string.connections_retention_24h
                else -> R.string.connections_retention_8h
            })
        }
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        val currentIndex = retentionHoursOptions.indexOf(retentionHours).coerceAtLeast(0)
        spinner.setSelection(currentIndex)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                retentionHours = retentionHoursOptions.getOrNull(position) ?: return
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun toggleSortOrder() {
        sortNewestFirst = !sortNewestFirst
        lastSnapshot?.let { snapshot ->
            launch {
                patchConnections(snapshot)
                withContext(Dispatchers.Main) {
                    binding.recyclerList.scrollToPosition(0)
                }
            }
        }
    }
}
