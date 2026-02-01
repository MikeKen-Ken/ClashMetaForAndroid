package com.github.kr328.clash

import com.github.kr328.clash.common.ProxyGroupRefresh
import com.github.kr328.clash.common.log.DebugLog
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

        // 多次延迟刷新悬浮信息，确保配置加载和选择恢复后能获取到正确数据
        launch {
            delay(300)
            design.requests.send(ProxyDesign.Request.ReloadFloating)
            delay(700)
            design.requests.send(ProxyDesign.Request.ReloadFloating)
            delay(1500)
            design.requests.send(ProxyDesign.Request.ReloadFloating)
        }

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
                        ProxyDesign.Request.ReloadFloating -> {
                            design.updateCurrentNodeFloatingInfo()
                        }
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)

                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            launch {
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
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }
                                val state = states[it.index]
                                val groupName = names[it.index]
                                DebugLog.d("ProxyFloating", "Reload done index=${it.index} groupName=\"$groupName\" group.now=\"${group.now}\" state.now before=\"${state.now}\"")
                                state.now = group.now
                                state.nowIsManual = group.nowIsManual
                                DebugLog.d("ProxyFloating", "Reload done state.now after=\"${state.now}\" proxies.size=${group.proxies.size}")

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