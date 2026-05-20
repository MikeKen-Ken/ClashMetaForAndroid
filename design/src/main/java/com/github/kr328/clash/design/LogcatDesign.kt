package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
            val recycler = binding.recyclerList
            val mgr = recycler.layoutManager as? LinearLayoutManager
            val followNewest = streaming && mgr != null && mgr.isFollowingNewest(adapter.itemCount.coerceAtLeast(0))

            adapter.messages = messages

            adapter.notifyItemRangeInserted(adapter.messages.size, appended)
            adapter.notifyItemRangeRemoved(0, removed)

            if (followNewest) {
                mgr.scrollToNewestEnd(messages.size)
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
            applySortOrder()
        }
        binding.recyclerList.adapter = adapter

        binding.deleteView.setOnClickListener { requests.trySend(Request.Delete) }
        binding.exportView.setOnClickListener { requests.trySend(Request.Export) }
        binding.sortOrderView.setOnClickListener { toggleSortOrder() }
        binding.closeView.setOnClickListener { requests.trySend(Request.Close) }
    }

    private fun toggleSortOrder() {
        val recycler = binding.recyclerList
        val mgr = recycler.layoutManager as? LinearLayoutManager ?: return
        val count = adapter.itemCount
        val scrollTarget = when {
            mgr.isFollowingNewest(count) -> ScrollTarget.OLDEST
            mgr.isFollowingOldest(count) -> ScrollTarget.NEWEST
            else -> {
                val first = mgr.findFirstVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION) ScrollTarget.NEWEST
                else ScrollTarget.Mirrored((count - 1 - first).coerceIn(0, (count - 1).coerceAtLeast(0)))
            }
        }

        reversedOrder = !reversedOrder
        mgr.applySortOrder()

        if (count > 0) {
            recycler.post {
                val updated = recycler.layoutManager as? LinearLayoutManager ?: return@post
                when (scrollTarget) {
                    ScrollTarget.NEWEST -> updated.scrollToNewestEnd(count)
                    ScrollTarget.OLDEST -> updated.scrollToOldestEnd()
                    is ScrollTarget.Mirrored -> updated.scrollToPositionWithOffset(scrollTarget.position, 0)
                }
            }
        }
    }

    private fun LinearLayoutManager.applySortOrder() {
        reverseLayout = streaming != reversedOrder
        stackFromEnd = reverseLayout
    }

    /** 是否贴在「最新一条」所在端（随 reverseLayout 变化）。 */
    private fun LinearLayoutManager.isFollowingNewest(itemCount: Int): Boolean {
        if (itemCount <= 0) return true
        return if (reverseLayout) {
            findFirstVisibleItemPosition() >= itemCount - 1
        } else {
            findLastVisibleItemPosition() >= itemCount - 1
        }
    }

    /** 是否贴在「最旧一条」所在端。 */
    private fun LinearLayoutManager.isFollowingOldest(itemCount: Int): Boolean {
        if (itemCount <= 0) return false
        return if (reverseLayout) {
            findLastVisibleItemPosition() <= 0
        } else {
            findFirstVisibleItemPosition() <= 0
        }
    }

    private fun LinearLayoutManager.scrollToNewestEnd(itemCount: Int) {
        if (itemCount <= 0) return
        scrollToPositionWithOffset(itemCount - 1, 0)
    }

    private fun LinearLayoutManager.scrollToOldestEnd() {
        scrollToPositionWithOffset(0, 0)
    }

    private sealed class ScrollTarget {
        data object NEWEST : ScrollTarget()
        data object OLDEST : ScrollTarget()
        data class Mirrored(val position: Int) : ScrollTarget()
    }
}