package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
    initialLogLevel: LogMessage.Level?,
) : Design<LogcatDesign.Request>(context) {
    sealed class Request {
        object Close : Request()
        object Delete : Request()
        object Export : Request()
        data class ChangeLogLevel(val level: LogMessage.Level) : Request()
    }

    private val binding = DesignLogcatBinding
        .inflate(context.layoutInflater, context.root, false)
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
            if (streaming) {
                reverseLayout = true
                stackFromEnd = true
            }
        }
        binding.recyclerList.adapter = adapter

        val logLevels = arrayOf(
            LogMessage.Level.Debug,
            LogMessage.Level.Info,
            LogMessage.Level.Warning,
            LogMessage.Level.Error,
            LogMessage.Level.Silent,
        )
        val levelStrings = arrayOf(
            context.getString(R.string.debug),
            context.getString(R.string.info),
            context.getString(R.string.warning),
            context.getString(R.string.error),
            context.getString(R.string.silent),
        )
        val levelAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, levelStrings).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.logLevelSpinner.adapter = levelAdapter
        val initialIndex = logLevels.indexOf(initialLogLevel).coerceAtLeast(0)
        binding.logLevelSpinner.setSelection(initialIndex)
        binding.logLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                requests.trySend(Request.ChangeLogLevel(logLevels[position]))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.deleteView.setOnClickListener { requests.trySend(Request.Delete) }
        binding.exportView.setOnClickListener { requests.trySend(Request.Export) }
        binding.closeView.setOnClickListener { requests.trySend(Request.Close) }
    }
}