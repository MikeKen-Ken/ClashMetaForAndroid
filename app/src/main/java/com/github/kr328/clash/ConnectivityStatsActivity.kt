package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.connectivitysync.ConnectivitySyncSettingsMirror
import com.github.kr328.clash.connectivitysync.ConnectivitySyncSnapshots
import com.github.kr328.clash.design.ConnectivityStatsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ConnectivityMergeStatus
import com.github.kr328.clash.design.model.ConnectivityScoreRow
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.connectivitysync.ConnectivityStatsSync
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ConnectivityStatsActivity : BaseActivity<ConnectivityStatsDesign>() {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun main() {
        val rows = loadRows()
        val design = ConnectivityStatsDesign(
            this,
            rows,
            uiStore.connectivitySyncIntervalHours,
            ConnectivitySyncSnapshots.lastSyncAt(this),
        )
        setContentDesign(design)
        design.setMergeStatus(initialMergeStatus())
        val statusEvents = Channel<String>(Channel.CONFLATED)
        val statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val payload = intent?.getStringExtra(Intents.EXTRA_CONNECTIVITY_MERGE_STATUS)
                    ?: return
                statusEvents.trySend(payload)
            }
        }
        registerReceiverCompat(
            statusReceiver,
            IntentFilter(Intents.ACTION_CONNECTIVITY_MERGE_STATUS),
            Permissions.RECEIVE_SELF_BROADCASTS,
            null,
        )
        if (clashRunning) {
            launch {
                val live = runCatching { withClash { queryConnectivityMergeStatus() } }.getOrNull()
                if (live != null) design.setMergeStatus(decodeStatus(live))
            }
        }

        try {
        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded, Event.ServiceRecreated -> {
                            design.replaceRows(loadRows())
                        }
                        else -> Unit
                    }
                }
                statusEvents.onReceive { payload ->
                    val status = decodeStatus(payload)
                    design.setMergeStatus(status)
                    if (status.phase != ConnectivityMergeStatus.Phase.Running) {
                        design.setLastMergeAt(ConnectivitySyncSnapshots.lastSyncAt(this@ConnectivityStatsActivity))
                        design.replaceRows(loadRows())
                    }
                }
                design.requests.onReceive { request ->
                    when (request) {
                        is ConnectivityStatsDesign.Request.ClearOne -> {
                            withClash {
                                resetConnectivityStatistics(request.name)
                            }
                            design.replaceRows(loadRows())
                            design.showNativeToast(getString(R.string.connectivity_stats_clear_one, request.name))
                            setResult(RESULT_OK)
                        }
                        ConnectivityStatsDesign.Request.ClearAll -> {
                            AlertDialog.Builder(this@ConnectivityStatsActivity)
                                .setMessage(R.string.connectivity_stats_clear_all_message)
                                .setPositiveButton(R.string.connectivity_stats_clear) { _, _ ->
                                    launch {
                                        withClash { clearAllConnectivityStatistics() }
                                        design.replaceRows(loadRows())
                                        design.showNativeToast(getString(R.string.connectivity_stats_cleared_all))
                                        setResult(RESULT_OK)
                                    }
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                        ConnectivityStatsDesign.Request.Sync -> {
                            mergeConnectivityStatistics(design)
                        }
                        ConnectivityStatsDesign.Request.ChooseSyncInterval -> {
                            chooseSyncInterval()
                        }
                    }
                }
            }
        }
        } finally {
            unregisterReceiver(statusReceiver)
            statusEvents.close()
        }
    }

    private fun initialMergeStatus(): ConnectivityMergeStatus {
        val stored = ConnectivitySyncSnapshots.mergeStatus(this)
        val proxyRunning = StatusClient(this).currentProfile() != null
        return if (!proxyRunning && stored.phase == ConnectivityMergeStatus.Phase.Running) {
            stored.copy(
                phase = ConnectivityMergeStatus.Phase.Failed,
                progress = 100,
                error = stored.error ?: "Interrupted",
            )
        } else {
            stored
        }
    }

    private fun decodeStatus(payload: String): ConnectivityMergeStatus = runCatching {
        json.decodeFromString(ConnectivityMergeStatus.serializer(), payload)
    }.getOrDefault(ConnectivityMergeStatus())

    private suspend fun loadRows(): List<ConnectivityScoreRow> {
        val raw = withClash { queryProxyConnectivityStats() }
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ConnectivityScoreRow.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun mergeConnectivityStatistics(design: ConnectivityStatsDesign) {
        if (uiStore.webdavUrl.isBlank() || uiStore.webdavUsername.isBlank() || uiStore.webdavPassword.isBlank()) {
            design.showNativeToast(getString(R.string.connectivity_stats_sync_webdav_required))
            return
        }
        if (!uiStore.webdavUrl.trim().startsWith("https://", ignoreCase = true)) {
            design.showNativeToast(getString(R.string.connectivity_stats_sync_https_required))
            return
        }
        try {
            ConnectivitySyncSettingsMirror.push(this)
            val result = json.decodeFromString(
                MergeOutcome.serializer(),
                withClash { syncConnectivityStatistics() },
            )
            design.replaceRows(loadRows())
            design.setLastMergeAt(result.lastSyncAt)
            design.showNativeToast(
                resources.getQuantityString(
                    R.plurals.connectivity_stats_sync_success,
                    result.deviceCount,
                    result.deviceCount,
                ),
            )
            setResult(RESULT_OK)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            design.showNativeToast(
                getString(
                    R.string.connectivity_stats_sync_failed,
                    error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    @Serializable
    private data class MergeOutcome(
        val deviceCount: Int = 0,
        val proxyCount: Int = 0,
        val lastSyncAt: Long = 0,
    )

    private fun chooseSyncInterval() {
        val values = ConnectivityStatsSync.intervalOptions
        val labels = arrayOf(
            getString(R.string.connectivity_stats_interval_1h),
            getString(R.string.connectivity_stats_interval_6h),
            getString(R.string.connectivity_stats_interval_12h),
            getString(R.string.connectivity_stats_interval_24h),
            getString(R.string.connectivity_stats_interval_48h),
            getString(R.string.connectivity_stats_interval_7d),
        )
        val selected = values.indexOf(uiStore.connectivitySyncIntervalHours).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.connectivity_stats_sync_interval_title)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                uiStore.connectivitySyncIntervalHours = values[index]
                ConnectivitySyncSettingsMirror.push(this)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
