package com.github.kr328.clash.design

import android.content.Context
import android.os.Looper
import android.graphics.Rect
import android.view.View
import com.github.kr328.clash.common.log.DebugLog
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

    companion object {
        private const val TAG_FLOATING = "ProxyFloating"
    }

    private var floatingRetryCount = 0
    private val floatingRetryMax = 3
    private val floatingRetryDelayMs = 200L

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
            val currentItem = binding.pagesView.currentItem
            DebugLog.d(TAG_FLOATING, "updateGroup: position=$position currentItem=$currentItem match=${position == currentItem}")
            if (position == currentItem) {
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

    /**
     * 悬浮卡片点击：先请求当前页节点数据刷新悬浮信息，再滚动到当前节点
     */
    fun onFloatingCardClick() {
        val position = binding.pagesView.currentItem
        DebugLog.d(TAG_FLOATING, "onFloatingCardClick: position=$position, groupNames.size=${groupNames.size}, currentGroup=${if (position in groupNames.indices) groupNames[position] else "invalid"}")
        if (position in groupNames.indices) {
            requests.trySend(Request.Reload(position))
        }
        scrollToCurrentNode()
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: not main thread, post to main")
            binding.root.post { updateCurrentNodeFloatingInfo() }
            return
        }
        val position = binding.pagesView.currentItem
        DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: position=$position, groupNames.size=${groupNames.size}")
        if (position < 0 || position >= groupNames.size) {
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: EXIT position invalid")
            binding.currentGroupNameText.text = ""
            binding.currentNodeNameText.text = context.getString(R.string.loading)
            binding.currentNodeInfoDelayText.text = ""
            floatingRetryCount = 0
            return
        }
        val proxyAdapter = adapter.getProxyAdapter(position)
        binding.currentGroupNameText.text = groupNames[position]
        val statesSize = proxyAdapter.states.size
        if (proxyAdapter.states.isEmpty()) {
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: EXIT states.isEmpty, group=${groupNames[position]}, request Reload(position) + scheduleRetry")
            binding.currentNodeNameText.text = context.getString(R.string.loading)
            binding.currentNodeInfoDelayText.text = ""
            // 当前页数据未加载：主动请求该页 Reload，Reload 完成后 updateGroup 会再次刷新悬浮信息
            requests.trySend(Request.Reload(position))
            scheduleFloatingRetry()
            return
        }
        val currentNow = proxyAdapter.states.firstOrNull()?.currentGroupNow
        DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: states.size=$statesSize, currentNow=\"$currentNow\", proxyNames=${proxyAdapter.states.take(5).map { it.proxy.name }}")
        if (currentNow.isNullOrEmpty() || currentNow == "?") {
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: EXIT currentNow invalid (empty or \"?\"), request Reload(position) + scheduleRetry")
            binding.currentNodeNameText.text = context.getString(R.string.loading)
            binding.currentNodeInfoDelayText.text = ""
            // 当前页的 state.now 尚未从 core 同步：主动请求该页 Reload
            requests.trySend(Request.Reload(position))
            scheduleFloatingRetry()
            return
        }
        val selectedState = proxyAdapter.states.find { it.proxy.name == currentNow }
        if (selectedState == null) {
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: EXIT no proxy name match currentNow=\"$currentNow\", show name only")
            binding.currentNodeNameText.text = currentNow
            binding.currentNodeInfoDelayText.text = ""
            floatingRetryCount = 0
            return
        }
        selectedState.update(true)
        floatingRetryCount = 0
        val title = selectedState.title.ifEmpty { selectedState.proxy.name }
        binding.currentNodeNameText.text = title
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
        val card = binding.currentNodeFloatingCard
        DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: BEFORE card visibility=${card.visibility} elevation=${card.elevation} translationZ=${card.translationZ} isAttachedToWindow=${card.isAttachedToWindow}")
        card.visibility = View.VISIBLE
        val elevationDp = 24f
        val newElevation = elevationDp * context.resources.displayMetrics.density
        card.elevation = newElevation
        card.stateListAnimator = null
        DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: AFTER set card visibility=${card.visibility} elevation=${card.elevation}")
        DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: OK title=\"$title\" infoText=\"$infoText\"")
        card.post {
            val vis = card.visibility
            val w = card.width
            val h = card.height
            val shown = card.isShown
            val alpha = card.alpha
            val elev = card.elevation
            val tz = card.translationZ
            val attached = card.isAttachedToWindow
            val x = card.x
            val y = card.y
            val tx = card.translationX
            val ty = card.translationY
            val loc = IntArray(2)
            card.getLocationOnScreen(loc)
            val screenL = loc[0]
            val screenT = loc[1]
            val globalRect = Rect()
            val hasGlobalRect = card.getGlobalVisibleRect(globalRect)
            val windowRect = Rect()
            card.getWindowVisibleDisplayFrame(windowRect)
            val root = binding.root
            val rootW = root.width
            val rootH = root.height
            val bgColor = card.cardBackgroundColor?.defaultColor
            val nameColor = binding.currentNodeNameText.currentTextColor
            val infoColor = binding.currentNodeInfoDelayText.currentTextColor
            val groupColor = binding.currentGroupNameText.currentTextColor
            var p: View? = card.parent as? View
            val parentChain = buildString {
                while (p != null) {
                    append(p.javaClass.simpleName)
                    append("(v=")
                    append(p.visibility)
                    append(",elev=")
                    append(p.elevation)
                    append(");")
                    p = p.parent as? View
                }
            }
            val siblings = (card.parent as? android.view.ViewGroup)?.let { group ->
                (0 until group.childCount).map { i ->
                    val c = group.getChildAt(i)
                    "${c.javaClass.simpleName}(v=${c.visibility},elev=${c.elevation})"
                }.joinToString(",")
            } ?: "noParent"
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: POST card visibility=$vis size=${w}x${h} isShown=$shown alpha=$alpha elevation=$elev translationZ=$tz isAttached=$attached x=$x y=$y tx=$tx ty=$ty")
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: POST locationOnScreen=($screenL,$screenT) globalRect=$globalRect hasGlobalRect=$hasGlobalRect windowRect=$windowRect rootSize=${rootW}x${rootH}")
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: POST colors bg=${bgColor?.let { Integer.toHexString(it) }} name=${Integer.toHexString(nameColor)} info=${Integer.toHexString(infoColor)} group=${Integer.toHexString(groupColor)}")
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: POST parentChain=[$parentChain]")
            DebugLog.d(TAG_FLOATING, "updateCurrentNodeFloatingInfo: POST siblings=[$siblings]")
        }
    }

    private fun scheduleFloatingRetry() {
        if (floatingRetryCount >= floatingRetryMax) return
        floatingRetryCount += 1
        binding.root.postDelayed({ updateCurrentNodeFloatingInfo() }, floatingRetryDelayMs)
    }
}