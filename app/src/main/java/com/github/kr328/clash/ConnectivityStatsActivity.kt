package com.github.kr328.clash

import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.connectivitysync.ConnectivityStatsSync
import com.github.kr328.clash.connectivitysync.ConnectivitySyncBackoff
import com.github.kr328.clash.design.ConnectivityStatsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ConnectivityScoreRow
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ConnectivityStatsActivity : BaseActivity<ConnectivityStatsDesign>() {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun main() {
        val rows = loadRows()
        val design = ConnectivityStatsDesign(this, rows, uiStore.connectivitySyncIntervalHours)
        setContentDesign(design)

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
                design.requests.onReceive { request ->
                    when (request) {
                        is ConnectivityStatsDesign.Request.ClearOne -> {
                            ConnectivityStatsSync.reset(
                                this@ConnectivityStatsActivity,
                                listOf(request.name),
                            ) { resetWatermarks ->
                                withClash {
                                    clearProxyConnectivityStatsFor(request.name, resetWatermarks)
                                }
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
                                        val raw = withClash { exportProxyConnectivityStats() }
                                        val names = ConnectivityStatsSync.namesFromStatsPayload(raw)
                                        ConnectivityStatsSync.reset(
                                            this@ConnectivityStatsActivity,
                                            names,
                                            includeKnownResets = true,
                                        ) { resetWatermarks ->
                                            withClash {
                                                clearProxyConnectivityStats(resetWatermarks)
                                            }
                                        }
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
    }

    private suspend fun loadRows(): List<ConnectivityScoreRow> {
        val raw = withClash { queryProxyConnectivityStats() }
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ConnectivityScoreRow.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun mergeConnectivityStatistics(design: ConnectivityStatsDesign) {
        if (!ConnectivityStatsSync.hasCredentials(uiStore)) {
            design.showNativeToast(getString(R.string.connectivity_stats_sync_webdav_required))
            return
        }
        if (!ConnectivityStatsSync.isConfigured(uiStore)) {
            design.showNativeToast(getString(R.string.connectivity_stats_sync_https_required))
            return
        }
        try {
            val result = ConnectivityStatsSync.merge(
                context = this@ConnectivityStatsActivity,
                store = uiStore,
                mergeLocal = { previousOthers, remoteOthers, resetWatermarks ->
                    withClash {
                        mergeProxyConnectivityStats(
                            previousOthers,
                            remoteOthers,
                            resetWatermarks,
                        )
                    }
                },
            )
            design.replaceRows(loadRows())
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
                ConnectivitySyncBackoff.rememberSettings(uiStore)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
