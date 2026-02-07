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
    private val onCopy: ((Connection, String) -> Unit)? = null,
) : RecyclerView.Adapter<ConnectionAdapter.Holder>() {
    class Holder(val binding: AdapterConnectionItemBinding) : RecyclerView.ViewHolder(binding.root)

    var displayItems: List<ConnectionDisplayItem> = emptyList()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = displayItems[position].connection.id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterConnectionItemBinding.inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = displayItems[position]
        val conn = item.connection
        holder.binding.connection = conn
        holder.binding.networkDisplayText = conn.metadata?.network?.takeIf { it.isNotEmpty() }?.uppercase()
        holder.binding.trafficDisplayText = item.trafficDisplayText
        holder.binding.trafficDisplayView.visibility = View.VISIBLE
        holder.binding.startTimeDisplayText = item.startTimeDisplayText
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
        holder.binding.closeView.visibility = if (item.isClosed) View.GONE else View.VISIBLE
        holder.binding.closeView.setOnClickListener { onClose(conn) }
        holder.binding.root.setOnLongClickListener {
            onCopy?.invoke(conn, formatRuleDisplay(conn))
            true
        }
    }

    override fun getItemCount(): Int = displayItems.size

    private fun formatRuleDisplay(conn: Connection): String {
        return buildString {
            append(conn.chains)
            if (conn.rule.isNotEmpty()) {
                append(" --> [")
                append(ruleDisplayName(conn))
                if (conn.ruleDetail.isNotEmpty()) append(",").append(conn.ruleDetail)
                append("]")
            }
        }
    }

    /** For RuleSet, show rulePayload (e.g. proxy, cn) instead of generic "RuleSet". */
    private fun ruleDisplayName(conn: Connection): String {
        if (conn.rule.equals("RuleSet", ignoreCase = true) && conn.rulePayload.isNotEmpty()) {
            return conn.rulePayload
        }
        return conn.rule
    }
}
