package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.adapter.ConnectivityStatsAdapter
import com.github.kr328.clash.design.databinding.DesignConnectivityStatsBinding
import com.github.kr328.clash.design.model.ConnectivityScoreRow
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConnectivityStatsDesign(
    context: Context,
    rows: List<ConnectivityScoreRow>,
) : Design<ConnectivityStatsDesign.Request>(context) {
    sealed class Request {
        data class ClearOne(val name: String) : Request()
        object ClearAll : Request()
    }

    private val binding = DesignConnectivityStatsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private val adapter = ConnectivityStatsAdapter(context, rows) { row ->
        requests.trySend(Request.ClearOne(row.name))
    }

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.mainList.recyclerList.bindAppBarElevation(binding.activityBarLayout)
        binding.mainList.recyclerList.applyLinearAdapter(context, adapter)
    }

    fun requestClearAll() {
        requests.trySend(Request.ClearAll)
    }

    suspend fun replaceRows(rows: List<ConnectivityScoreRow>) {
        withContext(Dispatchers.Main) {
            adapter.replaceAll(rows)
        }
    }
}
