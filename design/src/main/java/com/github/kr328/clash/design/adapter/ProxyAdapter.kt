package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.component.ProxyView
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.component.isUsableProxyDelay

class ProxyAdapter(
    private val config: ProxyViewConfig,
    private val clicked: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<ProxyAdapter.Holder>() {
    class Holder(val view: ProxyView) : RecyclerView.ViewHolder(view)

    var selectable: Boolean = false
    var states: List<ProxyViewState> = emptyList()
    private var allStates: List<ProxyViewState> = emptyList()
    private var hideUnavailable: Boolean = false

    fun updateStates(states: List<ProxyViewState>) {
        allStates = states
        updateVisibleStates()
    }

    fun setHideUnavailable(enabled: Boolean) {
        if (hideUnavailable == enabled) return

        hideUnavailable = enabled
        updateVisibleStates()
    }

    private fun updateVisibleStates() {
        val visibleStates = if (hideUnavailable) {
            allStates.filter { isUsableProxyDelay(it.proxy.delay) }
        } else {
            allStates
        }

        if (states.size == visibleStates.size) {
            states = visibleStates
            notifyItemRangeChanged(0, visibleStates.size)
        } else {
            notifyItemRangeRemoved(0, states.size)
            states = visibleStates
            notifyItemRangeInserted(0, visibleStates.size)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ProxyView(config.context, config))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = states[position]

        holder.view.apply {
            state = current

            setOnClickListener {
                // 再次点击当前「手动选择」节点时，触发清除手动选择逻辑。
                clicked(current.proxy.name, current.isManualSelection)
            }

            val isSelector = selectable

            isFocusable = isSelector
            isClickable = isSelector

            current.update(true)
        }
    }

    override fun getItemCount(): Int {
        return states.size
    }
}
