package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.component.ProxyRegion
import com.github.kr328.clash.design.component.ProxyView
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.component.isProxyDelayWithinGroupTimeout

class ProxyAdapter(
    private val config: ProxyViewConfig,
    private val clicked: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<ProxyAdapter.Holder>() {
    class Holder(val view: ProxyView) : RecyclerView.ViewHolder(view)

    var selectable: Boolean = false
    var states: List<ProxyViewState> = emptyList()
    private var allStates: List<ProxyViewState> = emptyList()
    private var hideUnavailable: Boolean = false
    private var regionFilter: String = ""
    private var delayTimeoutMs: Int = 5000

    /** Stores the group timeout without redrawing. Call before [updateStates]. */
    fun bindDelayTimeout(timeoutMs: Int) {
        delayTimeoutMs = if (timeoutMs > 0) timeoutMs else 5000
    }

    fun proxyNames(): List<String> = allStates.map { it.proxy.name }

    /** Stores the filter without redrawing. Call before [updateStates]. */
    fun bindRegionFilter(flag: String) {
        regionFilter = flag
    }

    fun updateStates(states: List<ProxyViewState>) {
        allStates = states
        updateVisibleStates()
    }

    fun redraw() {
        if (states.isNotEmpty()) notifyItemRangeChanged(0, states.size)
    }

    fun setHideUnavailable(enabled: Boolean) {
        if (hideUnavailable == enabled) return

        hideUnavailable = enabled
        updateVisibleStates()
    }

    fun setRegionFilter(flag: String) {
        if (regionFilter == flag) return

        bindRegionFilter(flag)
        updateVisibleStates()
    }

    private fun updateVisibleStates() {
        var visibleStates = allStates
        if (regionFilter.isNotEmpty()) {
            visibleStates = visibleStates.filter {
                ProxyRegion.resolve(it.proxy.name) == regionFilter
            }
        }
        if (hideUnavailable) {
            visibleStates = visibleStates.filter {
                it.currentGroupNow == it.proxy.name ||
                    isProxyDelayWithinGroupTimeout(it.proxy.delay, delayTimeoutMs)
            }
        }

        val old = states
        states = visibleStates
        if (old.size != visibleStates.size || old.zip(visibleStates).any { it.first !== it.second }) {
            notifyDataSetChanged()
        } else if (visibleStates.isNotEmpty()) {
            notifyItemRangeChanged(0, visibleStates.size)
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
