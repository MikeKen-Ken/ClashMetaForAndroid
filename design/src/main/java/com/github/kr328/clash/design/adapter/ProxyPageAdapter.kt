package com.github.kr328.clash.design.adapter

import android.view.ViewGroup
import androidx.databinding.Observable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.BR
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.component.ProxyPageFactory
import com.github.kr328.clash.design.component.ProxyRegion
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.model.ProxyPageState
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.ui.Surface
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyPageAdapter(
    private val surface: Surface,
    private val config: ProxyViewConfig,
    private val adapters: List<ProxyAdapter>,
    private val stateChanged: (Int) -> Unit,
) : RecyclerView.Adapter<ProxyPageFactory.Holder>() {
    private val factory = ProxyPageFactory(config)
    private var parent: RecyclerView? = null

    val states = List(adapters.size) { ProxyPageState() }

    private val groupLoaded = BooleanArray(adapters.size)
    private var regionFilter: String = ""

    suspend fun updateAdapter(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        val states = withContext(Dispatchers.Default) {
            proxies.map {
                val link = if (it.type.group) links[it.name] else null

                ProxyViewState(config, it, parent, link)
            }
        }

        withContext(Dispatchers.Main) {
            groupLoaded[position] = true
            adapters[position].apply {
                this.selectable = selectable
                bindRegionFilter(regionFilter)
                updateStates(states)
            }

            requestRedrawVisible(position)
        }
    }

    fun setRegionFilter(flag: String) {
        regionFilter = flag
        adapters.forEach { it.setRegionFilter(flag) }
    }

    fun availableRegions(): List<ProxyRegion.Option> {
        val names = ArrayList<String>()
        adapters.forEachIndexed { index, adapter ->
            if (!groupLoaded[index]) return@forEachIndexed
            names.addAll(adapter.proxyNames())
        }
        return ProxyRegion.listAvailable(names)
    }

    fun allGroupsLoaded(): Boolean = groupLoaded.all { it }

    fun requestRedrawVisible(currentItem: Int) {
        val rv = parent ?: return
        val mgr = rv.layoutManager as? LinearLayoutManager ?: return
        val pageView = mgr.findViewByPosition(currentItem) ?: return
        factory.fromRoot(pageView).recyclerView.invalidateChildren()
    }

    /** Measured height of the overlay header (status bar inset included). */
    var listTopInset: Int = 0
        set(value) {
            if (field == value) return
            field = value
            holders.forEach { applyListPadding(it.recyclerView) }
        }

    private val holders = mutableListOf<ProxyPageFactory.Holder>()
    private val listGap = config.context.getPixels(R.dimen.proxy_layout_padding)

    init {
        surface.addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                if (propertyId == BR.insets) {
                    holders.forEach { applyListPadding(it.recyclerView) }
                }
            }
        })
    }

    private fun applyListPadding(recyclerView: RecyclerView) {
        recyclerView.setPaddingRelative(
            0,
            listTopInset + listGap,
            0,
            surface.insets.bottom + listGap,
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxyPageFactory.Holder {
        val holder = factory.newInstance()

        holders.add(holder)
        applyListPadding(holder.recyclerView)
        holder.recyclerView.addScrolledToBottomObserver { view, bottom ->
            val position = view.position
            val state = states[position]

            if (state.bottom != bottom) {
                state.bottom = bottom

                stateChanged(position)
            }
        }

        return holder
    }

    override fun onBindViewHolder(holder: ProxyPageFactory.Holder, position: Int) {
        val adapter = adapters[position]

        states[position].bottom = false

        holder.recyclerView.apply {
            this.position = position
            this.swapAdapter(adapter, false)
        }
    }

    override fun getItemCount(): Int {
        return adapters.size
    }

    fun getProxyAdapter(position: Int): ProxyAdapter = adapters[position]

    fun setHideUnavailable(enabled: Boolean) {
        adapters.forEach { it.setHideUnavailable(enabled) }
    }

    fun redrawAllProxies() {
        adapters.forEach { it.redraw() }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.parent = recyclerView

        recyclerView.isFocusable = false
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.parent = null
    }


    private var RecyclerView.position: Int
        get() {
            return tag as? Int ?: -1
        }
        set(value) {
            tag = value
        }
}
