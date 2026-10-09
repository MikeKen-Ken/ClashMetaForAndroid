package com.github.kr328.clash.design.component

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.button.MaterialButton

/**
 * One button on the proxy page. Tapping it opens a bottom drawer of countries.
 */
class ProxyRegionBar(
    private val button: MaterialButton,
    private val allLabel: String,
    private val onSelected: (String) -> Unit,
) {
    private var regions: List<ProxyRegion.Option> = emptyList()
    private var selectedFlag: String = ""

    init {
        button.setOnClickListener { openDrawer() }
    }

    fun render(regions: List<ProxyRegion.Option>, selectedFlag: String) {
        val shown = ArrayList<ProxyRegion.Option>(regions.size + 1)
        shown.addAll(regions)
        if (selectedFlag.isNotEmpty() && regions.none { it.flag == selectedFlag }) {
            ProxyRegion.label(selectedFlag)?.let { label ->
                shown.add(ProxyRegion.Option(selectedFlag, label))
            }
        }
        this.regions = shown
        this.selectedFlag = if (shown.any { it.flag == selectedFlag }) selectedFlag else ""
        button.text = button.context.getString(R.string.proxy_region_button, labelOf(this.selectedFlag))
        button.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun labelOf(flag: String): String {
        if (flag.isEmpty()) return allLabel
        val match = regions.find { it.flag == flag } ?: return flag
        return "${match.flag} ${match.label}"
    }

    private fun openDrawer() {
        if (regions.isEmpty()) return
        val context = button.context
        val dialog = AppBottomSheetDialog(context)
        val content = context.layoutInflater.inflate(R.layout.dialog_proxy_region, null, false)
        val list = content.findViewById<RecyclerView>(R.id.region_list)
        val screenHeight = context.resources.displayMetrics.heightPixels
        list.layoutParams.height = (screenHeight * 0.62f).toInt()
        val options = ArrayList<ProxyRegion.Option>(regions.size + 1)
        options.add(ProxyRegion.Option("", allLabel))
        options.addAll(regions)
        val selectedIndex = options.indexOfFirst { it.flag == selectedFlag }.coerceAtLeast(0)
        list.applyLinearAdapter(
            context,
            RegionAdapter(options, selectedFlag) { flag ->
                dialog.dismiss()
                if (flag != selectedFlag) onSelected(flag)
            },
        )
        dialog.setContentView(content)
        dialog.show()
        dialog.behavior.isDraggable = false
        list.post { list.scrollToPosition(selectedIndex) }
    }

    private class RegionAdapter(
        private val options: List<ProxyRegion.Option>,
        private val selectedFlag: String,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<RegionAdapter.Holder>() {
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.region_label)
            val check: ImageView = view.findViewById(R.id.region_check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = parent.context.layoutInflater.inflate(R.layout.adapter_proxy_region, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val option = options[position]
            holder.label.text = if (option.flag.isEmpty()) option.label else "${option.flag} ${option.label}"
            holder.check.visibility = if (option.flag == selectedFlag) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnClickListener { onClick(option.flag) }
        }

        override fun getItemCount(): Int = options.size
    }
}
