package com.github.kr328.clash.design.adapter

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import com.github.kr328.clash.design.component.ProxyRegion
import com.github.kr328.clash.design.component.ProxyView
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.component.ProxyViewState
import com.github.kr328.clash.design.component.isProxyDelayWithinGroupTimeout

enum class ProxyNodeTap {
    Select,
    TestPinned,
    Unpin,
}

class ProxyAdapter(
    private val config: ProxyViewConfig,
    private val clicked: (String, ProxyNodeTap) -> Unit,
) : RecyclerView.Adapter<ProxyAdapter.Holder>() {
    class Holder(val view: ProxyView) : RecyclerView.ViewHolder(view)

    private val tapHandler = Handler(Looper.getMainLooper())
    private var pendingTapName: String? = null
    private var pendingTapAt = 0L
    private val confirmTap = Runnable {
        val name = pendingTapName ?: return@Runnable
        pendingTapName = null
        clicked(name, ProxyNodeTap.TestPinned)
    }

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

            val isSelector = selectable
            isFocusable = isSelector
            isClickable = isSelector
            if (isSelector && current.isManualSelection) {
                bindPinnedTouch(this, current)
            } else {
                setOnTouchListener(null)
                setOnClickListener {
                    if (isSelector) clicked(current.proxy.name, ProxyNodeTap.Select)
                }
            }

            current.update(true)
        }
    }

    override fun getItemCount(): Int {
        return states.size
    }

    private fun bindPinnedTouch(view: ProxyView, state: ProxyViewState) {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var tap = false
        view.setOnClickListener(null)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    tap = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tap && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                        tap = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (tap) onPinnedClick(state.proxy.name)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    tap = false
                    true
                }
                else -> false
            }
        }
    }

    private fun onPinnedClick(name: String) {
        val now = SystemClock.uptimeMillis()
        if (name == pendingTapName && now - pendingTapAt <= DOUBLE_TAP_MS) {
            tapHandler.removeCallbacks(confirmTap)
            pendingTapName = null
            clicked(name, ProxyNodeTap.Unpin)
            return
        }
        tapHandler.removeCallbacks(confirmTap)
        pendingTapName = name
        pendingTapAt = now
        tapHandler.postDelayed(confirmTap, DOUBLE_TAP_MS)
    }

    private companion object {
        const val DOUBLE_TAP_MS = 500L
    }
}
