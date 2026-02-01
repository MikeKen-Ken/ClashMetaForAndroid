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
    private val groupNames: List<String>,
    uiStore: UiStore,
) : Design<ProxyDesign.Request>(context) {

    sealed class Request {
        object ReloadAll : Request()
        object ReloadFloating : Request()
        object ReLaunch : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
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

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        adapter.updateAdapter(position, proxies, selectable, parent, links)

        // 确保在主线程更新 UI
        withContext(Dispatchers.Main) {
            adapter.states[position].urlTesting = false

            updateUrlTestButtonStatus()
            // 只有当更新的是当前页时才更新悬浮信息
            if (position == binding.pagesView.currentItem) {
                updateCurrentNodeFloatingInfo()
            }
        }
    }

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            adapter.requestRedrawVisible()
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
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
            val mode = when {
                !isChecked -> null
                checkedId == R.id.rule_mode_btn -> TunnelState.Mode.Rule
                checkedId == R.id.global_mode_btn -> TunnelState.Mode.Global
                checkedId == R.id.direct_mode_btn -> TunnelState.Mode.Direct
                else -> return@addOnButtonCheckedListener
            }
            requests.trySend(Request.PatchMode(mode))
        }

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE

            binding.currentNodeFloatingCard.visibility = View.GONE
            binding.urlTestView.visibility = View.GONE
            binding.tabLayoutView.visibility = View.GONE
            binding.elevationView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
        } else {
            binding.currentNodeFloatingCard.visibility = View.VISIBLE
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
                        updateCurrentNodeFloatingInfo()
                    }
                })
            }

            TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
                tab.text = groupNames[index]
            }.attach()

            val initialPosition = groupNames.indexOf(uiStore.proxyLastGroup)

            binding.pagesView.post {
                if (initialPosition > 0)
                    binding.pagesView.setCurrentItem(initialPosition, false)
                updateCurrentNodeFloatingInfo()
            }
        }
    }

    fun requestUrlTesting() {
        urlTesting = true

        requests.trySend(Request.UrlTest(binding.pagesView.currentItem))

        updateUrlTestButtonStatus()
    }

    fun scrollToCurrentNode() {
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

    fun updateCurrentNodeFloatingInfo() {
        val position = binding.pagesView.currentItem
        if (position < 0 || position >= groupNames.size) {
            // 无效的 position，清空显示
            binding.currentGroupNameText.text = ""
            binding.currentNodeNameText.text = context.getString(R.string.loading)
            binding.currentNodeInfoDelayText.text = ""
            return
        }
        val proxyAdapter = adapter.getProxyAdapter(position)
        binding.currentGroupNameText.text = groupNames[position]
        // 如果 states 为空，说明数据还在加载中，显示加载提示
        if (proxyAdapter.states.isEmpty()) {
            binding.currentNodeNameText.text = context.getString(R.string.loading)
            binding.currentNodeInfoDelayText.text = ""
            return
        }
        val currentNow = proxyAdapter.states.firstOrNull()?.currentGroupNow
        // 如果 currentNow 无效（空、"?" 或找不到对应节点），尝试显示实际选中的节点
        if (currentNow.isNullOrEmpty() || currentNow == "?") {
            binding.currentNodeNameText.text = context.getString(R.string.loading)
            binding.currentNodeInfoDelayText.text = ""
            return
        }
        val selectedState = proxyAdapter.states.find { it.proxy.name == currentNow } ?: run {
            // 如果找不到对应节点，可能是嵌套代理组，直接显示名称
            binding.currentNodeNameText.text = currentNow
            binding.currentNodeInfoDelayText.text = ""
            return
        }
        selectedState.update(true)
        // 确保 title 不为空
        binding.currentNodeNameText.text = selectedState.title.ifEmpty { selectedState.proxy.name }
        val delayStr = when {
            selectedState.delayTimeout -> "Timeout"
            selectedState.delayText.isEmpty() -> ""
            else -> "${selectedState.delayText} ms"
        }
        val infoText = when {
            selectedState.subtitle.isEmpty() && delayStr.isEmpty() -> ""
            selectedState.subtitle.isEmpty() -> delayStr
            delayStr.isEmpty() -> selectedState.subtitle
            else -> "${selectedState.subtitle} · $delayStr"
        }
        binding.currentNodeInfoDelayText.text = infoText
    }
}