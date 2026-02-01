package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.common.log.DebugLog
import com.github.kr328.clash.design.adapter.DebugEntryAdapter
import com.github.kr328.clash.design.databinding.DesignDebugBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DebugDesign(context: Context) : Design<DebugDesign.Request>(context) {

    sealed class Request {
        object Clear : Request()
    }

    private val binding = DesignDebugBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = DebugEntryAdapter()

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.recyclerList.layoutManager = LinearLayoutManager(context)
        binding.recyclerList.adapter = adapter
    }

    suspend fun patchEntries(entries: List<DebugLog.Entry>) {
        withContext(Dispatchers.Main) {
            adapter.entries = entries
        }
    }

    fun clearEntries() {
        DebugLog.clear()
        adapter.entries = emptyList()
    }

    fun requestClear() {
        requests.trySend(Request.Clear)
    }
}
