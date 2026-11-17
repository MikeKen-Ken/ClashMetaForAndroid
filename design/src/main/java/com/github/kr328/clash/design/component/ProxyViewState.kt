package com.github.kr328.clash.design.component

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.model.ProxyState
import kotlin.math.absoluteValue
import kotlin.math.max

class ProxyViewState(
    val config: ProxyViewConfig,
    val proxy: Proxy,
    private val parent: ProxyState,
    private val link: ProxyState?
) {
    val paint = Paint()
    val rect = Rect()
    val path = Path()

    var title: String = ""
    var subtitle: String = ""
    var delayText: String = ""
    var effectiveDelayText: String = ""
    var delayTimeout: Boolean = false
    var background: Int = config.unselectedBackground
    var controls: Int = config.unselectedControl
    val isSelected: Boolean get() = selected

    private var delay: Int = Int.MIN_VALUE  // 使用不可能的值确保第一次 update() 时 delayText 被设置
    private var effectiveDelay: Int = Int.MIN_VALUE
    private var selected: Boolean = false
    /** True when this node is the current one AND was manually selected (show manual icon). */
    val isManualSelection: Boolean get() = selected && parent.nowIsManual

    /** Current selected node name of this group (for UI e.g. scroll-to-current). */
    val currentGroupNow: String get() = parent.now
    private var parentNow: String = ""
    private var parentNowIsManual: Boolean = false
    private var linkNow: String? = null

    private var lastFrameTime = System.currentTimeMillis()

    fun update(snap: Boolean): Boolean {
        val frameTime = System.currentTimeMillis()
        var invalidate = false

        if (proxy.type.group) {
            title = proxy.name

            if (link == null) {
                subtitle = proxy.type.name
            } else {
                if (linkNow !== link.now) {
                    linkNow = link.now

                    subtitle = "%s(%s)".format(
                        proxy.type.name,
                        link.now.ifEmpty { "*" }
                    )
                }
            }
        } else {
            title = proxy.title
            subtitle = proxy.subtitle
        }

        if (delay != proxy.delay) {
            delay = proxy.delay
            if (isUsableProxyDelay(proxy.delay)) {
                delayText = proxy.delay.toString()
                delayTimeout = false
            } else {
                delayText = "T"
                delayTimeout = true
            }

            // 嵌套组没有可探测的延迟：不显示"超时"，避免把可用的分组画成 T
            if (proxy.type.group && !isUsableProxyDelay(proxy.delay)) {
                delayText = ""
                delayTimeout = false
            }
        }

        if (effectiveDelay != proxy.effectiveDelay) {
            effectiveDelay = proxy.effectiveDelay
            effectiveDelayText = if (proxy.effectiveDelay >= 0) {
                proxy.effectiveDelay.toString()
            } else {
                ""
            }
        }

        if (parentNow !== parent.now || parentNowIsManual != parent.nowIsManual) {
            parentNow = parent.now
            parentNowIsManual = parent.nowIsManual
            // 始终高亮 core 的「当前节点」；仅当该节点为手动选择时显示手动标志
            selected = proxy.name == parent.now
        }

        controls = if (selected) config.selectedControl else config.unselectedControl

        if (snap) {
            background = if (selected) config.selectedBackground else config.unselectedBackground
        } else {
            val target = if (selected) config.selectedBackground else config.unselectedBackground

            if (background != target) {
                val sa = Color.alpha(background)
                val sr = Color.red(background)
                val sg = Color.green(background)
                val sb = Color.blue(background)

                val ta = Color.alpha(target)
                val tr = Color.red(target)
                val tg = Color.green(target)
                val tb = Color.blue(target)

                val da = ta - sa
                val dr = tr - sr
                val dg = tg - sg
                val db = tb - sb

                val max = max(
                    da.absoluteValue,
                    max(
                        dr.absoluteValue,
                        max(
                            dg.absoluteValue,
                            db.absoluteValue
                        )
                    )
                )

                val frameOffset = frameTime - lastFrameTime

                val colorOffset = (frameOffset / max.toFloat().coerceAtLeast(0.001f))
                    .coerceIn(0.0f, 1.0f)

                background = if (colorOffset > 0.999f) {
                    target
                } else {
                    Color.argb(
                        (sa + da * colorOffset).toInt(),
                        (sr + dr * colorOffset).toInt(),
                        (sg + dg * colorOffset).toInt(),
                        (sb + db * colorOffset).toInt()
                    )
                }

                invalidate = true
            }
        }

        lastFrameTime = frameTime

        return invalidate
    }
}

/** 1 until 0xffff = 成功延迟。未测速与超时均为 0xffff（显示为 T）。 */
fun isUsableProxyDelay(delay: Int): Boolean = delay in 1 until 0xffff

/**
 * 嵌套组与内置出站（DIRECT / REJECT 等）没有可探测的延迟：
 * 它们不会超时，也不该被"隐藏超时节点"过滤掉。
 */
fun Proxy.isAlwaysAvailable(): Boolean =
    type.group ||
        type == Proxy.Type.Direct ||
        type == Proxy.Type.Reject ||
        type == Proxy.Type.RejectDrop ||
        type == Proxy.Type.Pass ||
        type == Proxy.Type.Compatible

/** Desktop hide-unavailable: a measured delay stays visible only while it is under the group timeout. */
fun isProxyDelayWithinGroupTimeout(delay: Int, timeoutMs: Int): Boolean {
    val timeout = if (timeoutMs > 0) timeoutMs else 5000
    return delay > 0 && delay < timeout
}
