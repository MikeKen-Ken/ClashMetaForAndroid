package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.databinding.AdapterLogMessageBinding
import com.github.kr328.clash.design.util.layoutInflater

private const val LOG_SEPARATOR = " --> "

class LogMessageAdapter(
    private val context: Context,
    private val copy: (LogMessage) -> Unit,
) :
    RecyclerView.Adapter<LogMessageAdapter.Holder>() {
    class Holder(val binding: AdapterLogMessageBinding) : RecyclerView.ViewHolder(binding.root)

    var messages: List<LogMessage> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterLogMessageBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = messages[position]

        holder.binding.message = current
        holder.binding.root.setOnLongClickListener {
            copy(current)
            true
        }

        val packageName = parsePackageFromMessage(current.message)
        val iconView = holder.binding.appIconView
        if (packageName != null) {
            try {
                val icon = context.packageManager.getApplicationIcon(packageName)
                iconView.setImageDrawable(icon)
                iconView.visibility = View.VISIBLE
            } catch (_: Exception) {
                iconView.visibility = View.GONE
            }
        } else {
            iconView.visibility = View.GONE
        }
    }

    private fun parsePackageFromMessage(message: String): String? {
        val segments = message.split(LOG_SEPARATOR)
        if (segments.size >= 3) {
            val candidate = segments[1].trim()
            if (candidate.isNotEmpty() && ':' !in candidate) {
                return candidate
            }
        }
        return null
    }

    override fun getItemCount(): Int {
        return messages.size
    }
}