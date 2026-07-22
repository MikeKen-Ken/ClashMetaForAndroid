package com.github.kr328.clash

import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.ConnectivityStatsDesign
import com.github.kr328.clash.design.model.ConnectivityScoreRow
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ConnectivityStatsActivity : BaseActivity<ConnectivityStatsDesign>() {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun main() {
        val rows = loadRows()
        val design = ConnectivityStatsDesign(this, rows)
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
                            withClash { clearProxyConnectivityStatsFor(request.name) }
                            design.replaceRows(loadRows())
                            design.showNativeToast("已清空「${request.name}」的测速统计")
                            setResult(RESULT_OK)
                        }
                        ConnectivityStatsDesign.Request.ClearAll -> {
                            AlertDialog.Builder(this@ConnectivityStatsActivity)
                                .setMessage("确定清空所有节点的测速成功/失败统计吗？此操作不可恢复。")
                                .setPositiveButton("清空") { _, _ ->
                                    launch {
                                        withClash { clearProxyConnectivityStats() }
                                        design.replaceRows(loadRows())
                                        design.showNativeToast("已清空节点测速统计")
                                        setResult(RESULT_OK)
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
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
}
