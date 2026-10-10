package com.github.kr328.clash.design.adapter

import android.content.Context
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.layoutInflater

class ConnectivityMergeHeaderAdapter(
    private val context: Context,
    private var lastMergeAt: Long,
) : RecyclerView.Adapter<ConnectivityMergeHeaderAdapter.Holder>() {
    class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

    fun setLastMergeAt(value: Long) {
        if (lastMergeAt == value) return
        lastMergeAt = value
        notifyItemChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = context.layoutInflater
            .inflate(R.layout.adapter_connectivity_merge_header, parent, false) as TextView
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.view.text = if (lastMergeAt > 0L) {
            val time = DateUtils.formatDateTime(
                context,
                lastMergeAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_ABBREV_MONTH,
            )
            context.getString(R.string.connectivity_stats_last_merge, time)
        } else {
            context.getString(R.string.connectivity_stats_last_merge_never)
        }
    }

    override fun getItemCount(): Int = 1
}
