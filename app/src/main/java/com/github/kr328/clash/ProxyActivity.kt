package com.github.kr328.clash

import com.github.kr328.clash.common.ProxyGroupRefresh
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val states = List(names.size) { ProxyState("?", false) }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(3)

        val design = ProxyDesign(
            this,
            mode,
            names,
            uiStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

        while (isActive) {
            select<Unit> {
                ProxyGroupRefresh.channel.onReceive { groupName ->
                    val idx = names.indexOf(groupName)
                    if (idx >= 0) design.requests.send(ProxyDesign.Request.Reload(idx))
                }
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            // 每次 activity 恢复到前台时刷新数据
                            design.requests.send(ProxyDesign.Request.ReloadAll)
                        }
                        Event.ProfileLoaded, Event.ClashStart, Event.ServiceRecreated -> {
                            val newNames = withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            }

                            if (newNames != names) {
                                startActivity(ProxyActivity::class.intent)

                                finish()
                            } else {
                                // 代理组名称没变，但选择可能已恢复，重新加载所有数据
                                design.requests.send(ProxyDesign.Request.ReloadAll)
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)

                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            launch {
                                if (names.isEmpty()) return@launch
                                val priority =
                                    names.indexOf(uiStore.proxyLastGroup).let { if (it >= 0) it else 0 }
                                design.requests.send(ProxyDesign.Request.Reload(priority))
                                names.indices.forEach { idx ->
                                    if (idx != priority) {
                                        delay(45)
                                        design.requests.send(ProxyDesign.Request.Reload(idx))
                                    }
                                }
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                if (it.index !in names.indices) return@launch
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }
                                val state = states[it.index]
                                state.now = group.now
                                state.nowIsManual = group.nowIsManual

                                design.updateGroup(
                                    it.index,
                                    group.proxies,
                                    group.type == Proxy.Type.Selector ||
                                        group.type == Proxy.Type.Fallback ||
                                        group.type == Proxy.Type.URLTest,
                                    state,
                                    unorderedStates
                                )
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            withClash {
                                patchSelector(names[it.index], it.name)

                                states[it.index].now = it.name
                                states[it.index].nowIsManual = true
                            }

                            design.requestRedrawVisible()

                            // 切换节点后重新拉取该组数据，使界面上的延迟等字段与 core 同步更新
                            launch {
                                val idx = it.index
                                suspend fun reloadGroup() {
                                    val g = reloadLock.withPermit {
                                        withClash {
                                            queryProxyGroup(names[idx], uiStore.proxySort)
                                        }
                                    }
                                    val st = states[idx]
                                    st.now = g.now
                                    st.nowIsManual = g.nowIsManual
                                    design.updateGroup(
                                        idx,
                                        g.proxies,
                                        g.type == Proxy.Type.Selector ||
                                            g.type == Proxy.Type.Fallback ||
                                            g.type == Proxy.Type.URLTest,
                                        st,
                                        unorderedStates
                                    )
                                }
                                reloadGroup()
                                // 选择后 3.5s 时查询一次，仅刷新当前选中节点所在组
                                delay(3500L)
                                if (idx in names.indices) reloadGroup()
                            }
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                withClash {
                                    healthCheck(names[it.index])
                                }

                                design.requests.send(ProxyDesign.Request.ReloadAll)
                            }
                        }
                        is ProxyDesign.Request.PatchMode -> {
                            design.showModeSwitchTips()

                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)

                                o.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                    }
                }
            }
        }
    }
}