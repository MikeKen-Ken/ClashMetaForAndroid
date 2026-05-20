package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.component.ProxyView
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState

class ProxyAdapter(
    private val config: ProxyViewConfig,
    /** 选中组内叶子节点 */
    private val onSelect: (String) -> Unit,
    /** 在当前行已显示手动图标时取消手动固定 */
    private val onClearManualSelection: () -> Unit,
) : RecyclerView.Adapter<ProxyAdapter.Holder>() {
    class Holder(val view: ProxyView) : RecyclerView.ViewHolder(view)

    var selectable: Boolean = false
    var states: List<ProxyViewState> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ProxyView(config.context, config))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = states[position]

        holder.view.apply {
            state = current

            setOnClickListener {
                onSelect(current.proxy.name)
            }

            onManualIconClick = {
                if (selectable && current.isManualSelection) {
                    onClearManualSelection()
                }
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
