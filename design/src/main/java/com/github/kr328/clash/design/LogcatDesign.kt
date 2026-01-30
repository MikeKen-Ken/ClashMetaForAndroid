package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.adapter.LogMessageAdapter
import com.github.kr328.clash.design.databinding.DesignLogcatBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatDesign(
    context: Context,
    private val streaming: Boolean,
) : Design<LogcatDesign.Request>(context) {
    sealed class Request {
        object Close : Request()
        object Delete : Request()
        object Export : Request()
    }

    private val binding = DesignLogcatBinding
        .inflate(context.layoutInflater, context.root, false)

    /** When true, display order is reversed (oldest-first when streaming; newest-first when file). */
    private var reversedOrder = false

    private val adapter = LogMessageAdapter(context) {
        launch {
            val data = ClipData.newPlainText("log_message", it.message)

            context.getSystemService<ClipboardManager>()?.setPrimaryClip(data)

            showToast(R.string.copied, ToastDuration.Short)
        }
    }

    suspend fun patchMessages(messages: List<LogMessage>, removed: Int, appended: Int) {
        withContext(Dispatchers.Main) {
            adapter.messages = messages

            adapter.notifyItemRangeInserted(adapter.messages.size, appended)
            adapter.notifyItemRangeRemoved(0, removed)

            if (streaming && binding.recyclerList.isTop) {
                binding.recyclerList.scrollToPosition(messages.size - 1)
            }
        }
    }

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.streaming = streaming

        binding.activityBarLayout.applyFrom(context)

        binding.recyclerList.bindAppBarElevation(binding.activityBarLayout)

        binding.recyclerList.layoutManager = LinearLayoutManager(context).apply {
            applySortOrder(false)
        }
        binding.recyclerList.adapter = adapter

        binding.deleteView.setOnClickListener { requests.trySend(Request.Delete) }
        binding.exportView.setOnClickListener { requests.trySend(Request.Export) }
        binding.sortOrderView.setOnClickListener { toggleSortOrder() }
        binding.closeView.setOnClickListener { requests.trySend(Request.Close) }
    }

    private fun toggleSortOrder() {
        reversedOrder = !reversedOrder
        (binding.recyclerList.layoutManager as? LinearLayoutManager)?.applySortOrder(true)
    }

    private fun LinearLayoutManager.applySortOrder(scrollToTop: Boolean) {
        reverseLayout = streaming != reversedOrder
        stackFromEnd = reverseLayout
        if (scrollToTop) {
            binding.recyclerList.scrollToPosition(0)
        }
    }
}