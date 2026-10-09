package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.ProxyAdapter
import com.github.kr328.clash.design.adapter.ProxyPageAdapter
import com.github.kr328.clash.design.component.ProxyPageFactory
import com.github.kr328.clash.design.component.ProxyRegionBar
import com.github.kr328.clash.design.component.ProxyToolsDrawer
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.LiquidGlass
import com.github.kr328.clash.design.util.root
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    proxyAdsBlock: Boolean,
    private val groupNames: List<String>,
    private val uiStore: UiStore,
) : Design<ProxyDesign.Request>(context) {

    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()
        object FlushFakeIpCache : Request()
        object ClearConnectivityStats : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class PatchAdsBlock(val enabled: Boolean) : Request()
        data class PatchTimeout(val timeoutMs: Int) : Request()
        data class PatchConcurrency(val concurrency: Int) : Request()
        data class Reload(val index: Int) : Request()
        data class Select(
            val index: Int,
            val name: String,
            val clearManualSelection: Boolean = false,
        ) : Request()
        data class UrlTest(val index: Int) : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine).also {
        it.showDetail = uiStore.proxyShowDetail
    }

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private var urlTesting: Boolean
        get() = adapter.states[binding.pagesView.currentItem].urlTesting
        set(value) {
            adapter.states[binding.pagesView.currentItem].urlTesting = value
        }

    private val toolsDrawer = ProxyToolsDrawer(
        binding.toolsButton,
        uiStore,
        overrideMode,
        proxyAdsBlock,
    ) { requests.trySend(it) }

    private val regionBar = ProxyRegionBar(
        binding.regionFilterButton,
        context.getString(R.string.proxy_region_all),
    ) { flag ->
        if (uiStore.proxyRegionFilter == flag) return@ProxyRegionBar
        uiStore.proxyRegionFilter = flag
        if (groupNames.isNotEmpty()) adapter.setRegionFilter(flag)
    }

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        adapter.updateAdapter(position, proxies, selectable, parent, links)

        withContext(Dispatchers.Main) {
            adapter.states[position].urlTesting = false
            binding.tabLayoutView.getTabAt(position)?.text =
                formatGroupName(groupNames[position], parent)
            updateUrlTestButtonStatus()
            refreshRegionBar()
        }
    }

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            adapter.requestRedrawVisible(binding.pagesView.currentItem)
        }
    }

    suspend fun showModeSwitchTips() {
        showNativeToast(R.string.mode_switch_tips, Toast.LENGTH_LONG)
    }

    /** VPN 重启或从主进程恢复后，与 [com.github.kr328.clash.design.store.UiStore.proxyUiMode] 对齐模式按钮，不触发 PatchMode。 */
    suspend fun syncModeToggle(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            toolsDrawer.syncMode(mode)
        }
    }

    init {
        binding.self = this

        binding.hideUnavailableView.apply {
            isActivated = uiStore.proxyHideUnavailable
            alpha = if (uiStore.proxyHideUnavailable) 1f else 0.55f
            contentDescription = context.getString(
                if (uiStore.proxyHideUnavailable) R.string.show_unavailable_proxies
                else R.string.hide_unavailable_proxies
            )
        }
        applyShowDetailButton()

        binding.activityBarLayout.applyFrom(context)
        LiquidGlass.attach(binding.controlsBarLayout)
        LiquidGlass.attach(binding.tabLayoutView)
        LiquidGlass.attach(binding.elevationView)

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.scrollToCurrentFab.visibility = View.GONE
            binding.urlTestView.visibility = View.GONE
            binding.hideUnavailableView.visibility = View.GONE
            binding.showDetailView.visibility = View.GONE
            binding.controlsBarLayout.visibility = View.GONE
            binding.tabLayoutView.visibility = View.GONE
            binding.elevationView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
        } else {
            binding.scrollToCurrentFab.visibility = View.VISIBLE
            binding.pagesView.apply {
                adapter = ProxyPageAdapter(
                    surface,
                    config,
                    List(groupNames.size) { index ->
                        ProxyAdapter(config) { name, clearManualSelection ->
                            requests.trySend(Request.Select(index, name, clearManualSelection))
                        }
                    }
                ) {
                    if (it == currentItem)
                        updateUrlTestButtonStatus()
                }
                this@ProxyDesign.adapter.setHideUnavailable(uiStore.proxyHideUnavailable)
                this@ProxyDesign.adapter.setRegionFilter(uiStore.proxyRegionFilter)

                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) {
                        updateUrlTestButtonStatus()
                    }

                    override fun onPageSelected(position: Int) {
                        uiStore.proxyLastGroup = groupNames[position]
                    }
                })
            }

            // 节点网格在半透明头部下方滚动，顶部留白取头部实测高度（随字体缩放/控制条变化）
            binding.headerLayout.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                adapter.listTopInset = bottom - top
            }

            TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
                tab.text = groupNames[index]
            }.attach()

            val initialPosition = groupNames.indexOf(uiStore.proxyLastGroup)

            if (initialPosition > 0) {
                binding.pagesView.post {
                    binding.pagesView.setCurrentItem(initialPosition, false)
                }
            }
        }
    }

    fun requestUrlTesting() {
        urlTesting = true

        requests.trySend(Request.UrlTest(binding.pagesView.currentItem))

        updateUrlTestButtonStatus()
    }

    fun requestFlushFakeIpCache() {
        requests.trySend(Request.FlushFakeIpCache)
    }

    fun requestClearConnectivityStats() {
        requests.trySend(Request.ClearConnectivityStats)
    }

    fun toggleShowDetail() {
        val showDetail = !uiStore.proxyShowDetail
        uiStore.proxyShowDetail = showDetail
        config.showDetail = showDetail
        applyShowDetailButton()
        adapter.redrawAllProxies()
    }

    fun toggleHideUnavailable() {
        val hideUnavailable = !uiStore.proxyHideUnavailable
        uiStore.proxyHideUnavailable = hideUnavailable
        adapter.setHideUnavailable(hideUnavailable)
        binding.hideUnavailableView.apply {
            isActivated = hideUnavailable
            alpha = if (hideUnavailable) 1f else 0.55f
            contentDescription = context.getString(
                if (hideUnavailable) R.string.show_unavailable_proxies
                else R.string.hide_unavailable_proxies
            )
        }
    }

    suspend fun showFlushFakeIpDone() {
        showNativeToast(R.string.flush_fake_ip_done, Toast.LENGTH_SHORT)
    }

    fun onScrollToCurrentClick() {
        val position = binding.pagesView.currentItem
        val proxyAdapter = adapter.getProxyAdapter(position)
        val currentNow = proxyAdapter.states.firstOrNull()?.currentGroupNow ?: return
        val index = proxyAdapter.states.indexOfFirst { it.proxy.name == currentNow }
        if (index < 0) return
        val innerRv = binding.pagesView.getChildAt(0) as? RecyclerView ?: return
        val pageHolder = innerRv.findViewHolderForAdapterPosition(position) as? ProxyPageFactory.Holder ?: return
        pageHolder.recyclerView.smoothScrollToPosition(index)
    }

    private fun applyShowDetailButton() {
        val showDetail = uiStore.proxyShowDetail
        binding.showDetailView.apply {
            setImageResource(
                if (showDetail) R.drawable.ic_baseline_visibility
                else R.drawable.ic_baseline_hide
            )
            alpha = if (showDetail) 1f else 0.55f
            contentDescription = context.getString(
                if (showDetail) R.string.hide_proxy_detail
                else R.string.show_proxy_detail
            )
        }
    }

    private fun updateUrlTestButtonStatus() {
        if (urlTesting) {
            binding.urlTestView.visibility = View.GONE
            binding.urlTestProgressView.visibility = View.VISIBLE
        } else {
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE
        }
    }

    private fun refreshRegionBar() {
        val regions = adapter.availableRegions()
        val selected = uiStore.proxyRegionFilter
        if (
            selected.isNotEmpty() &&
            adapter.allGroupsLoaded() &&
            regions.none { it.flag == selected }
        ) {
            uiStore.proxyRegionFilter = ""
            adapter.setRegionFilter("")
            regionBar.render(regions, "")
            return
        }
        regionBar.render(regions, selected)
    }

    private fun formatGroupName(name: String, state: ProxyState): String {
        return if (state.maxConnectTimes > 0) {
            "$name (${state.connectTimes}/${state.maxConnectTimes})"
        } else {
            name
        }
    }
}
