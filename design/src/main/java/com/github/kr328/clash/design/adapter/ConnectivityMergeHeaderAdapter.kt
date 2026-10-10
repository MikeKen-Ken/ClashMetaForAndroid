package com.github.kr328.clash.design.adapter

import android.content.Context
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ConnectivityMergeStatus
import com.github.kr328.clash.design.model.ConnectivityMergeStatus.Phase
import com.github.kr328.clash.design.util.layoutInflater

class ConnectivityMergeHeaderAdapter(
    private val context: Context,
    private var lastMergeAt: Long,
) : RecyclerView.Adapter<ConnectivityMergeHeaderAdapter.Holder>() {
    class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

    private var status = ConnectivityMergeStatus()

    fun setLastMergeAt(value: Long) {
        if (lastMergeAt == value) return
        lastMergeAt = value
        notifyItemChanged(0)
    }

    fun setStatus(value: ConnectivityMergeStatus) {
        if (status == value) return
        status = value
        notifyItemChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = context.layoutInflater
            .inflate(R.layout.adapter_connectivity_merge_header, parent, false) as TextView
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.view.text = buildLines().joinToString("\n")
    }

    override fun getItemCount(): Int = 1

    private fun buildLines(): List<String> {
        val lines = mutableListOf<String>()
        val finished = status.finishedAt > 0L
        when (status.phase) {
            Phase.Running -> lines += context.getString(
                R.string.connectivity_stats_merge_running,
                status.progress,
            )
            Phase.Success -> if (finished) {
                lines += context.getString(
                    R.string.connectivity_stats_merge_success,
                    formatTime(status.finishedAt),
                )
            }
            Phase.Partial -> if (finished) {
                lines += context.getString(
                    R.string.connectivity_stats_merge_partial,
                    formatTime(status.finishedAt),
                    status.skipped.size,
                )
            }
            Phase.Failed -> if (finished) {
                lines += context.getString(
                    R.string.connectivity_stats_merge_failed,
                    formatTime(status.finishedAt),
                    status.error.orEmpty(),
                )
            }
            Phase.Idle -> Unit
        }

        if (status.phase == Phase.Success || status.phase == Phase.Partial) {
            if (status.pulled.isEmpty()) {
                lines += context.getString(R.string.connectivity_stats_merge_no_devices)
            }
            status.pulled.forEach {
                lines += context.getString(
                    R.string.connectivity_stats_merge_pulled,
                    it.device,
                    formatTime(it.updatedAt),
                    it.proxyCount,
                )
            }
            status.skipped.forEach {
                lines += context.getString(R.string.connectivity_stats_merge_skipped, it)
            }
        } else {
            lines += if (lastMergeAt > 0L) {
                context.getString(R.string.connectivity_stats_last_merge, formatTime(lastMergeAt))
            } else {
                context.getString(R.string.connectivity_stats_last_merge_never)
            }
        }
        return lines
    }

    private fun formatTime(unixMillis: Long): String = DateUtils.formatDateTime(
        context,
        unixMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
}
