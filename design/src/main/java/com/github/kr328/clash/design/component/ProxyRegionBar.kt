package com.github.kr328.clash.design.component

import android.view.View
import android.widget.HorizontalScrollView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * One scrollable row of country chips. The first chip clears the filter.
 */
class ProxyRegionBar(
    private val scroll: HorizontalScrollView,
    private val group: MaterialButtonToggleGroup,
    private val allLabel: String,
    private val onSelected: (String) -> Unit,
) {
    private var suppress = false
    private var shownFlags: List<String> = emptyList()
    private val buttonIdByFlag = HashMap<String, Int>()

    init {
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (suppress || !isChecked) return@addOnButtonCheckedListener
            val flag = group.findViewById<View>(checkedId)?.tag as? String ?: return@addOnButtonCheckedListener
            onSelected(flag)
        }
    }

    fun render(regions: List<ProxyRegion.Option>, selectedFlag: String) {
        val shown = ArrayList<ProxyRegion.Option>(regions.size + 1)
        shown.addAll(regions)
        if (selectedFlag.isNotEmpty() && regions.none { it.flag == selectedFlag }) {
            ProxyRegion.label(selectedFlag)?.let { label ->
                shown.add(ProxyRegion.Option(selectedFlag, label))
            }
        }
        val flags = shown.map { it.flag }
        val flagsChanged = flags != shownFlags
        if (flagsChanged) {
            rebuild(shown)
            shownFlags = flags
        }
        val effective = if (buttonIdByFlag.containsKey(selectedFlag)) selectedFlag else ""
        val selectionChanged = check(effective)
        scroll.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        if (shown.isNotEmpty() && (flagsChanged || selectionChanged)) {
            scrollSelectedIntoView()
        }
    }

    private fun rebuild(regions: List<ProxyRegion.Option>) {
        suppress = true
        group.isSelectionRequired = false
        group.removeAllViews()
        buttonIdByFlag.clear()
        addChip("", allLabel)
        for (region in regions) {
            addChip(region.flag, "${region.flag} ${region.label}")
        }
        group.isSelectionRequired = true
        suppress = false
    }

    private fun addChip(flag: String, text: String) {
        val button = scroll.context.layoutInflater
            .inflate(R.layout.proxy_region_chip, group, false) as MaterialButton
        val id = View.generateViewId()
        button.id = id
        button.tag = flag
        button.text = text
        group.addView(button)
        buttonIdByFlag[flag] = id
    }

    private fun check(flag: String): Boolean {
        val id = buttonIdByFlag[flag] ?: return false
        if (group.checkedButtonId == id) return false
        suppress = true
        group.check(id)
        suppress = false
        return true
    }

    private fun scrollSelectedIntoView() {
        val id = group.checkedButtonId
        if (id == View.NO_ID) return
        scroll.post {
            val button = group.findViewById<View>(id) ?: return@post
            val target = (button.left - scroll.paddingStart).coerceAtLeast(0)
            if (scroll.scrollX != target) scroll.scrollTo(target, 0)
        }
    }
}
