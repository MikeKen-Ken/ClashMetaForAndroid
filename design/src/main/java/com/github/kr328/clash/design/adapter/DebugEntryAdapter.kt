package com.github.kr328.clash.design.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.common.log.DebugLog
import com.github.kr328.clash.design.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugEntryAdapter : RecyclerView.Adapter<DebugEntryAdapter.Holder>() {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    var entries: List<DebugLog.Entry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.adapter_debug_entry, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = entries[position]
        holder.tagView.text = e.tag
        holder.messageView.text = e.message
        holder.timeView.text = timeFormat.format(Date(e.timeMillis))
    }

    override fun getItemCount(): Int = entries.size

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val tagView: TextView = view.findViewById(R.id.debug_tag)
        val messageView: TextView = view.findViewById(R.id.debug_message)
        val timeView: TextView = view.findViewById(R.id.debug_time)
    }
}
