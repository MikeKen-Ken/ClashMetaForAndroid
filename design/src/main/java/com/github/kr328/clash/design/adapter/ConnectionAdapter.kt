package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.databinding.AdapterConnectionItemBinding
import com.github.kr328.clash.design.util.layoutInflater

class ConnectionAdapter(
    private val context: Context,
    private val onClose: (Connection) -> Unit,
) : RecyclerView.Adapter<ConnectionAdapter.Holder>() {
    class Holder(val binding: AdapterConnectionItemBinding) : RecyclerView.ViewHolder(binding.root)

    var connections: List<Connection> = emptyList()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = connections[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterConnectionItemBinding.inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val conn = connections[position]
        holder.binding.connection = conn
        val ruleText = formatRuleDisplay(conn)
        holder.binding.ruleDisplayText = ruleText
        holder.binding.ruleDisplayView.visibility = if (ruleText.isNotEmpty()) View.VISIBLE else View.GONE
        val processView = holder.binding.processView
        val process = conn.metadata?.process?.takeIf { it.isNotEmpty() }
        processView.text = process
        processView.visibility = if (process != null) View.VISIBLE else View.GONE
        val iconView = holder.binding.appIconView
        if (process != null) {
            try {
                iconView.setImageDrawable(context.packageManager.getApplicationIcon(process))
                iconView.visibility = View.VISIBLE
            } catch (_: Exception) {
                iconView.visibility = View.GONE
            }
        } else {
            iconView.visibility = View.GONE
        }
        holder.binding.closeView.setOnClickListener { onClose(conn) }
    }

    override fun getItemCount(): Int = connections.size

    private fun formatRuleDisplay(conn: Connection): String {
        return buildString {
            append(conn.chains)
            if (conn.rule.isNotEmpty()) {
                append(" --> [")
                append(conn.rule)
                if (conn.ruleDetail.isNotEmpty()) append(",").append(conn.ruleDetail)
                append("]")
            }
        }
    }
}
