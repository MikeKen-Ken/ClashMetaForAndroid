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
import com.github.kr328.clash.design.connections.compactRejectClosedEntries
import com.github.kr328.clash.design.connections.rejectDedupeKey
import com.github.kr328.clash.design.connections.upsertClosedEntry
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.store.ClosedConnectionsStorage
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
import com.github.kr328.clash.design.landevices.LanRemoteDeviceFilter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private val RETENTION_HOURS_OPTIONS = listOf(1, 3, 8, 24)

class ConnectionsDesign(
    context: Context,
    private val uiStore: UiStore,
) : Design<ConnectionsDesign.Request>(context) {
    sealed class Request {
        data class CloseConnection(val connection: Connection) : Request()
        data class DisconnectDevice(val sourceIp: String) : Request()
        data class DisableDevice(val sourceIp: String) : Request()
        data class EnableDevice(val sourceIp: String) : Request()
        object ClearClosedConnections : Request()
        object CloseAllConnections : Request()
        object CloseConnectionsExcludingDirect : Request()
    }

    private enum class TabMode {
        Active,
        Closed,
        Devices,
    }

    private enum class DeviceAction {
        Disconnect,
        Disable,
        Cancel,
    }

    private val binding = DesignConnectionsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ConnectionAdapter(
        context,
        onClose = { conn ->
            val sourceIp = conn.metadata?.sourceIP.orEmpty()
            if (conn.id.startsWith("blocked-device:") && sourceIp.isNotEmpty()) {
                requests.trySend(Request.EnableDevice(sourceIp))
            } else if (conn.id.startsWith("device:") && sourceIp.isNotEmpty()) {
                launch {
                    when (showDeviceActionDialog()) {
                        DeviceAction.Disconnect -> requests.trySend(Request.DisconnectDevice(sourceIp))
                        DeviceAction.Disable -> requests.trySend(Request.DisableDevice(sourceIp))
                        DeviceAction.Cancel -> Unit
                    }
                }
            } else {
                requests.trySend(Request.CloseConnection(conn))
            }
        },
        onCopy = { displayText ->
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

    /** 已关闭连接（带时间戳）；内存与 [ClosedConnectionsStorage] 同步，持久化保留 24 小时 */
    private val closedEntries = mutableListOf<ClosedEntry>()
    private var closedPersistScheduled = false
    private var tabMode: TabMode = TabMode.Active
    var mergeByDomain: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (tabMode == TabMode.Closed) launch { refreshDisplayItems() }
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
            launch {
                if (tabMode == TabMode.Closed) refreshDisplayItems()
                withContext(Dispatchers.Main) { updateTabLabels() }
            }
        }

    val activeCount: Int get() = lastSnapshot?.connections?.size ?: 0
    /** 与已关闭列表相同的保留时长过滤 */
    val closedCount: Int
        get() {
            val retentionMs = retentionHours * 3600 * 1000L
            val nowMs = System.currentTimeMillis()
            return closedEntries.count { nowMs - it.closedAt <= retentionMs }
        }

    override val root: View
        get() = binding.root

    /** 进入连接页时从磁盘恢复已关闭列表（与桌面端 IndexedDB 恢复一致） */
    suspend fun loadPersistedClosedEntries() {
        val loaded = ClosedConnectionsStorage.load(context).compactRejectClosedEntries()
        closedEntries.clear()
        closedEntries.addAll(loaded)
        withContext(Dispatchers.Main) { updateTabLabels() }
    }

    suspend fun patchConnections(snapshot: ConnectionsSnapshot) {
        var previous = lastSnapshot
        val nowMs = System.currentTimeMillis()

        if (previous == null) {
            previous = LastConnectionsSnapshotStorage.load(context)
        }

        var closedPersistChanged = false
        val currentIds = snapshot.connections.map { it.id }.toSet()
        val existingClosedIds = closedEntries.map { it.connection.id }.toSet()
        if (previous != null) {
            previous.connections
                .filter { it.id !in currentIds && it.id !in existingClosedIds }
                .forEach { conn ->
                    if (closedEntries.upsertClosedEntry(ClosedEntry(conn, nowMs))) {
                        closedPersistChanged = true
                    }
                }
        }

        if (mergeRecentClosedFromSnapshot(snapshot, existingClosedIds, currentIds)) {
            closedPersistChanged = true
        }

        val persistRetentionMs = ClosedConnectionsStorage.PERSIST_RETENTION_HOURS * 3600 * 1000L
        val beforePrune = closedEntries.size
        closedEntries.removeAll { nowMs - it.closedAt > persistRetentionMs }
        if (closedEntries.size != beforePrune) closedPersistChanged = true
        if (closedPersistChanged) schedulePersistClosedEntries()

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
            updateTabLabels()
            if (tabMode == TabMode.Active) {
                adapter.patchDataSet(adapter::displayItems, displayItems, detectMove = true) { it.connection.id }
            } else {
                refreshDisplayItems()
            }
        }
    }

    private suspend fun refreshDisplayItems() {
        withContext(Dispatchers.Main) {
            val items = when (tabMode) {
                TabMode.Active -> buildActiveDisplayItems()
                TabMode.Closed -> buildClosedDisplayItems()
                TabMode.Devices -> buildDeviceDisplayItems()
            }
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

    private fun buildDeviceDisplayItems(): List<ConnectionDisplayItem> {
        val snapshot = lastSnapshot ?: return emptyList()
        val localIps = LanRemoteDeviceFilter.collectLocalInterfaceIps()
        val remoteLanConnections = snapshot.connections.filter {
            LanRemoteDeviceFilter.isRemoteLanClient(it, localIps)
        }
        val groups = remoteLanConnections
            .filter { !it.metadata?.sourceIP.isNullOrEmpty() }
            .groupBy { it.metadata?.sourceIP ?: "" }
        val activeItems = groups.entries
            .sortedByDescending { it.value.size }
            .map { (sourceIp, group) ->
                val latest = group.maxByOrNull { it.start } ?: group.first()
                val latestHostPort = formatHostPort(
                    latest.metadata?.host.orEmpty(),
                    latest.metadata?.destinationPort.orEmpty(),
                )
                val merged = latest.copy(
                    id = "device:$sourceIp",
                    upload = group.sumOf { it.upload },
                    download = group.sumOf { it.download },
                    metadata = latest.metadata?.copy(host = latestHostPort),
                )
                val trafficLine = context.getString(
                    R.string.connections_device_conn_count,
                    group.size,
                )
                ConnectionDisplayItem(
                    connection = merged,
                    trafficDisplayText = trafficLine,
                    startTimeDisplayText = sourceIp,
                    isClosed = false,
                )
            }
        val blockedItems = uiStore.lanBlockedSourceIps
            .filter { blockedIp -> blockedIp.isNotBlank() && blockedIp !in groups.keys }
            .sorted()
            .map { blockedIp ->
                val blockedConn = Connection(
                    id = "blocked-device:$blockedIp",
                    metadata = com.github.kr328.clash.core.model.ConnectionMetadata(sourceIP = blockedIp),
                )
                ConnectionDisplayItem(
                    connection = blockedConn,
                    trafficDisplayText = context.getString(R.string.connections_device_blocked),
                    startTimeDisplayText = blockedIp,
                    isClosed = false,
                )
            }
        return activeItems + blockedItems
    }

    private fun formatHostPort(host: String, destinationPort: String): String {
        val h = host.trim()
        val p = destinationPort.trim()
        if (h.isEmpty()) return h
        if (p.isEmpty()) return h
        return "$h:$p"
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

    /** 合并内核 REJECT 短时缓冲，避免连接存活时间短于轮询间隔而漏记 */
    private fun mergeRecentClosedFromSnapshot(
        snapshot: ConnectionsSnapshot,
        existingClosedIds: Set<String>,
        currentIds: Set<String>,
    ): Boolean {
        if (snapshot.recentClosed.isEmpty()) return false
        var changed = false
        val knownIds = existingClosedIds.toMutableSet()
        for (recent in snapshot.recentClosed) {
            if (recent.id in currentIds) continue
            if (recent.id in knownIds && recent.toConnection().rejectDedupeKey() == null) continue
            if (closedEntries.upsertClosedEntry(ClosedEntry(recent.toConnection(), recent.closedAt))) {
                knownIds.add(recent.id)
                changed = true
            }
        }
        return changed
    }

    fun clearClosedConnections() {
        closedEntries.clear()
        launch { ClosedConnectionsStorage.save(context, emptyList()) }
        launch {
            if (tabMode == TabMode.Closed) refreshDisplayItems()
            withContext(Dispatchers.Main) { updateTabLabels() }
        }
    }

    private fun schedulePersistClosedEntries() {
        if (closedPersistScheduled) return
        closedPersistScheduled = true
        launch {
            closedPersistScheduled = false
            ClosedConnectionsStorage.save(context, closedEntries.toList())
        }
    }

    fun onTabActiveClick() {
        tabMode = TabMode.Active
        launch { refreshDisplayItems() }
        updateTabAndToolbarVisibility()
    }

    fun onTabClosedClick() {
        tabMode = TabMode.Closed
        launch { refreshDisplayItems() }
        updateTabAndToolbarVisibility()
    }

    fun onTabDeviceClick() {
        tabMode = TabMode.Devices
        launch { refreshDisplayItems() }
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

    /** 离开连接页时持久化活跃快照与已关闭列表（与桌面端 setConnectionSnapshot 一致） */
    suspend fun persistState() {
        lastSnapshot?.let { LastConnectionsSnapshotStorage.save(context, it) }
        ClosedConnectionsStorage.save(context, closedEntries.toList())
    }

    private fun updateTabAndToolbarVisibility() {
        binding.tabActiveButton?.isSelected = tabMode == TabMode.Active
        binding.tabClosedButton?.isSelected = tabMode == TabMode.Closed
        binding.tabDeviceButton?.isSelected = tabMode == TabMode.Devices
        binding.closedToolbar?.visibility = if (tabMode == TabMode.Closed) View.VISIBLE else View.GONE
        binding.activeToolbar?.visibility = if (tabMode == TabMode.Closed) View.GONE else View.VISIBLE
        updateTabLabels()
    }

    private fun updateTabLabels() {
        binding.tabActiveButton?.text =
            "${context.getString(R.string.connections_active)} $activeCount"
        binding.tabClosedButton?.text =
            "${context.getString(R.string.connections_closed)} $closedCount"
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

    private suspend fun showDeviceActionDialog(): DeviceAction {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { ctx ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.connections_device_action_title)
                    .setPositiveButton(R.string.connections_disconnect_device) { _, _ ->
                        ctx.resume(DeviceAction.Disconnect)
                    }
                    .setNeutralButton(R.string.connections_disable_device) { _, _ ->
                        ctx.resume(DeviceAction.Disable)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        ctx.resume(DeviceAction.Cancel)
                    }
                    .setOnDismissListener {
                        if (!ctx.isCompleted) ctx.resume(DeviceAction.Cancel)
                    }
                    .show()
            }
        }
    }
}
