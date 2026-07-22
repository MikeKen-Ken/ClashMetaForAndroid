package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterConnectivityStatsBinding
import com.github.kr328.clash.design.model.ConnectivityScoreRow
import com.github.kr328.clash.design.util.layoutInflater

class ConnectivityStatsAdapter(
    private val context: Context,
    private var rows: List<ConnectivityScoreRow>,
    private val requestClear: (ConnectivityScoreRow) -> Unit,
) : RecyclerView.Adapter<ConnectivityStatsAdapter.Holder>() {

    class Holder(val binding: AdapterConnectivityStatsBinding) : RecyclerView.ViewHolder(binding.root)

    fun replaceAll(newRows: List<ConnectivityScoreRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterConnectivityStatsBinding.inflate(context.layoutInflater, parent, false),
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.binding.row = row
        holder.binding.subtitle = formatSubtitle(row)
        holder.binding.clearEnabled = row.hasStats
        holder.binding.clearView.alpha = if (row.hasStats) 1f else 0.3f
        holder.binding.clear = android.view.View.OnClickListener {
            if (row.hasStats) {
                requestClear(row)
            }
        }
        holder.binding.executePendingBindings()
    }

    override fun getItemCount(): Int = rows.size

    companion object {
        fun formatSubtitle(row: ConnectivityScoreRow): String {
            if (!row.hasStats) return "无统计"
            val success = formatCount(row.weightedSuccess)
            val failure = formatCount(row.weightedFailure)
            val delay = if (row.effectiveAvgDelayMs.isFinite()) {
                "${row.effectiveAvgDelayMs.toInt()}ms"
            } else {
                "—"
            }
            return "分数 ${"%.3f".format(row.score)} · 成功 $success · 失败 $failure · 有效延迟 $delay"
        }

        private fun formatCount(n: Double): String {
            if (!n.isFinite() || n <= 0) return "0"
            return if (n >= 10) "%.0f".format(n) else "%.1f".format(n)
        }
    }
}
