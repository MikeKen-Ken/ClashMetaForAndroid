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
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.button.MaterialButtonToggleGroup
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

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class PatchAdsBlock(val enabled: Boolean) : Request()
        data class Reload(val index: Int) : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int) : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine)

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private var urlTesting: Boolean
        get() = adapter.states[binding.pagesView.currentItem].urlTesting
        set(value) {
            adapter.states[binding.pagesView.currentItem].urlTesting = value
        }

    private var suppressModeToggleEmit: Boolean = false

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
            updateUrlTestButtonStatus()
        }
    }

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            adapter.requestRedrawVisible(binding.pagesView.currentItem)
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
        }
    }

    /** VPN 重启或从主进程恢复后，与 [com.github.kr328.clash.design.store.UiStore.proxyUiMode] 对齐模式按钮，不触发 PatchMode。 */
    suspend fun syncModeToggle(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            suppressModeToggleEmit = true
            try {
                when (mode) {
                    TunnelState.Mode.Rule -> binding.modeToggleGroup.check(R.id.rule_mode_btn)
                    TunnelState.Mode.Global -> binding.modeToggleGroup.check(R.id.global_mode_btn)
                    TunnelState.Mode.Direct -> binding.modeToggleGroup.check(R.id.direct_mode_btn)
                    else -> binding.modeToggleGroup.clearChecked()
                }
            } finally {
                suppressModeToggleEmit = false
            }
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        // 先设置初始选中状态，再添加监听器，避免初始化时触发 tips
        when (overrideMode) {
            TunnelState.Mode.Rule -> binding.modeToggleGroup.check(R.id.rule_mode_btn)
            TunnelState.Mode.Global -> binding.modeToggleGroup.check(R.id.global_mode_btn)
            TunnelState.Mode.Direct -> binding.modeToggleGroup.check(R.id.direct_mode_btn)
            else -> binding.modeToggleGroup.clearChecked()
        }
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (suppressModeToggleEmit) return@addOnButtonCheckedListener
            val mode = when {
                !isChecked -> null
                checkedId == R.id.rule_mode_btn -> TunnelState.Mode.Rule
                checkedId == R.id.global_mode_btn -> TunnelState.Mode.Global
                checkedId == R.id.direct_mode_btn -> TunnelState.Mode.Direct
                else -> return@addOnButtonCheckedListener
            }
            requests.trySend(Request.PatchMode(mode))
        }

        // 初始化超时选择按钮组：根据持久化的偏好恢复选中状态
        when (uiStore.proxyDelayTestTimeoutMs) {
            250 -> binding.timeoutToggleGroup.check(R.id.timeout_250_btn)
            500 -> binding.timeoutToggleGroup.check(R.id.timeout_500_btn)
            1000 -> binding.timeoutToggleGroup.check(R.id.timeout_1000_btn)
            3000 -> binding.timeoutToggleGroup.check(R.id.timeout_3000_btn)
            else -> binding.timeoutToggleGroup.check(R.id.timeout_5000_btn)
        }
        binding.timeoutToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val timeoutMs = when (checkedId) {
                R.id.timeout_250_btn -> 250
                R.id.timeout_500_btn -> 500
                R.id.timeout_1000_btn -> 1000
                R.id.timeout_3000_btn -> 3000
                R.id.timeout_5000_btn -> 5000
                else -> return@addOnButtonCheckedListener
            }
            uiStore.proxyDelayTestTimeoutMs = timeoutMs
        }

        if (proxyAdsBlock) {
            binding.adsToggleGroup.check(R.id.ads_on_btn)
        } else {
            binding.adsToggleGroup.check(R.id.ads_off_btn)
        }
        binding.adsToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val enabled = when (checkedId) {
                R.id.ads_on_btn -> true
                R.id.ads_off_btn -> false
                else -> return@addOnButtonCheckedListener
            }
            requests.trySend(Request.PatchAdsBlock(enabled))
        }

        // 初始化并发数选择按钮组：根据持久化的偏好恢复选中状态
        when (uiStore.proxyDelayTestConcurrency) {
            10 -> binding.concurrencyToggleGroup.check(R.id.concurrency_10_btn)
            20 -> binding.concurrencyToggleGroup.check(R.id.concurrency_20_btn)
            40 -> binding.concurrencyToggleGroup.check(R.id.concurrency_40_btn)
            50 -> binding.concurrencyToggleGroup.check(R.id.concurrency_50_btn)
            else -> binding.concurrencyToggleGroup.check(R.id.concurrency_30_btn)
        }
        binding.concurrencyToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val concurrency = when (checkedId) {
                R.id.concurrency_10_btn -> 10
                R.id.concurrency_20_btn -> 20
                R.id.concurrency_30_btn -> 30
                R.id.concurrency_40_btn -> 40
                R.id.concurrency_50_btn -> 50
                else -> return@addOnButtonCheckedListener
            }
            uiStore.proxyDelayTestConcurrency = concurrency
        }

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.scrollToCurrentFab.visibility = View.GONE
            binding.urlTestView.visibility = View.GONE
            binding.timeoutScrollView.visibility = View.GONE
            binding.adsScrollView.visibility = View.GONE
            binding.concurrencyScrollView.visibility = View.GONE
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
                        ProxyAdapter(config) { name ->
                            requests.trySend(Request.Select(index, name))
                        }
                    }
                ) {
                    if (it == currentItem)
                        updateUrlTestButtonStatus()
                }

                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) {
                        updateUrlTestButtonStatus()
                    }

                    override fun onPageSelected(position: Int) {
                        uiStore.proxyLastGroup = groupNames[position]
                    }
                })
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

    private fun updateUrlTestButtonStatus() {
        if (urlTesting) {
            binding.urlTestView.visibility = View.GONE
            binding.urlTestProgressView.visibility = View.VISIBLE
        } else {
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE
        }
    }
}