package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.common.ProxyGroupRefresh
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.scheduleClashMutation
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ProxyActivity : BaseActivity<ProxyDesign>() {
    companion object {
        /** 与 config/custom-clash-script.js 中 DIRECT / FALLBACK 输出名一致；整组测速时跳过 */
        private val SKIP_DELAY_CHECK_GROUPS = setOf("Direct", "Final")

        private const val LOG_FLUSH_FAKE_IP = "FakeIpFlush"
    }

    override suspend fun main() {
        // 与电脑端一致：按钮状态用前端记录的 proxyUiMode，不依赖 core 返回的 mode
        val mode = uiStore.proxyUiMode
        val proxyAdsBlock = withClash {
            queryOverride(Clash.OverrideSlot.Session).proxyAdsBlock
                ?: queryOverride(Clash.OverrideSlot.Persist).proxyAdsBlock
                ?: true
        }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val states = List(names.size) { ProxyState("?", false) }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(3)
        val crossProcessRefresh = Channel<String>(Channel.UNLIMITED)
        val refreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intents.ACTION_PROXY_GROUP_REFRESH) return

                intent.getStringExtra(Intents.EXTRA_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { crossProcessRefresh.trySend(it) }
            }
        }

        val design = ProxyDesign(
            this,
            mode,
            proxyAdsBlock,
            names,
            uiStore
        )

        setContentDesign(design)

        val pendingRefreshGroups = linkedSetOf<String>()
        var refreshScheduled = false
        /** 当前正在执行组级测速的代理组名 */
        val urlTestingGroups = mutableSetOf<String>()
        /** 测速期间用户已手动切换的组 → 节点名；空字符串表示用户清钉 */
        val urlTestManualOverrides = mutableMapOf<String, String>()
        fun scheduleGroupReload(groupName: String) {
            if (names.indexOf(groupName) < 0) return

            pendingRefreshGroups.add(groupName)
            if (refreshScheduled) return

            refreshScheduled = true
            launch {
                delay(250L)
                val groups = pendingRefreshGroups.toList()
                pendingRefreshGroups.clear()
                refreshScheduled = false
                groups.forEach { name ->
                    val idx = names.indexOf(name)
                    if (idx >= 0) design.requests.send(ProxyDesign.Request.Reload(idx))
                }
            }
        }

        design.requests.send(ProxyDesign.Request.ReloadAll)
        registerReceiverCompat(
            refreshReceiver,
            IntentFilter().apply { addAction(Intents.ACTION_PROXY_GROUP_REFRESH) },
            Permissions.RECEIVE_SELF_BROADCASTS,
            null
        )
        try {
            while (isActive) {
                select<Unit> {
                    ProxyGroupRefresh.channel.onReceive { groupName ->
                        scheduleGroupReload(groupName)
                    }
                    crossProcessRefresh.onReceive { groupName ->
                        scheduleGroupReload(groupName)
                    }
                    events.onReceive {
                        when (it) {
                            Event.ActivityStart -> {
                                // 每次 activity 恢复到前台时刷新数据
                                design.requests.send(ProxyDesign.Request.ReloadAll)
                                launch { design.syncModeToggle(uiStore.proxyUiMode) }
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
                                    launch { design.syncModeToggle(uiStore.proxyUiMode) }
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
                                    state.connectTimes = group.connectTimes
                                    state.maxConnectTimes = group.maxConnectTimes

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
                                val groupName = names[it.index]
                                if (groupName in urlTestingGroups) {
                                    urlTestManualOverrides[groupName] =
                                        if (it.clearManualSelection) "" else it.name
                                }
                                withClash {
                                    if (it.clearManualSelection) {
                                        clearManualSelectionForGroup(groupName)
                                        // 清除手动选择后也关闭该组连接，让后续流量按当前自动选择重建
                                        closeConnectionsUsingProxyGroup(groupName)
                                        states[it.index].nowIsManual = false
                                    } else {
                                        patchSelector(groupName, it.name)
                                        // 切换组内节点后断开经过该组的既有连接，避免仍占用旧出站
                                        closeConnectionsUsingProxyGroup(groupName)

                                        states[it.index].now = it.name
                                        states[it.index].nowIsManual = true
                                    }
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
                                        st.connectTimes = g.connectTimes
                                        st.maxConnectTimes = g.maxConnectTimes
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
                                val groupName = names[it.index]
                                if (groupName in SKIP_DELAY_CHECK_GROUPS) {
                                    launch {
                                        design.requests.send(ProxyDesign.Request.Reload(it.index))
                                    }
                                } else {
                                    urlTestingGroups.add(groupName)
                                    urlTestManualOverrides.remove(groupName)
                                    launch {
                                        design.showNativeToast(
                                            getString(R.string.url_test_started, groupName),
                                        )
                                        try {
                                            val result = withClash {
                                                val timeoutMs = uiStore.proxyDelayTestTimeoutMs
                                                val delayTestResult = healthCheckWithTimeout(
                                                    groupName,
                                                    timeoutMs,
                                                    uiStore.proxyDelayTestConcurrency.coerceIn(1, 200),
                                                )

                                                if (delayTestResult.hasSuccess) {
                                                    val override = urlTestManualOverrides[groupName]
                                                    if (override != null) {
                                                        if (override.isEmpty()) {
                                                            clearManualSelectionForGroup(groupName)
                                                        } else {
                                                            patchSelector(groupName, override)
                                                        }
                                                    }

                                                    // 只把 DAO 选择同步进 persist，不要整包 Load/ApplyConfig。
                                                    applyManualConnectivityOrder()
                                                }
                                                delayTestResult
                                            }
                                            if (result.hasSuccess) {
                                                design.showNativeToast(
                                                    getString(
                                                        R.string.url_test_finished,
                                                        groupName,
                                                        result.succeeded,
                                                        result.tested,
                                                        result.elapsedMs,
                                                    ),
                                                )
                                            } else {
                                                design.showNativeToast(
                                                    getString(
                                                        R.string.url_test_failed,
                                                        groupName,
                                                        result.tested,
                                                        result.elapsedMs,
                                                    ),
                                                )
                                            }
                                        } catch (e: Exception) {
                                            design.showExceptionToast(e)
                                        } finally {
                                            urlTestingGroups.remove(groupName)
                                            urlTestManualOverrides.remove(groupName)
                                        }

                                        design.requests.send(ProxyDesign.Request.ReloadAll)
                                    }
                                }
                            }
                            is ProxyDesign.Request.PatchMode -> {
                                it.mode?.let { m -> uiStore.proxyUiMode = m }

                                scheduleClashMutation("代理模式") {
                                    val o = queryOverride(Clash.OverrideSlot.Session)

                                    o.mode = it.mode

                                    patchOverride(Clash.OverrideSlot.Session, o)
                                    // 切换模式后关闭所有连接，让流量按新规则重建
                                    closeAllConnections()
                                }
                            }
                            is ProxyDesign.Request.PatchAdsBlock -> {
                                scheduleClashMutation("代理广告拦截") {
                                    val persist = queryOverride(Clash.OverrideSlot.Persist)
                                    persist.proxyAdsBlock = it.enabled
                                    patchOverride(Clash.OverrideSlot.Persist, persist)
                                    val session = queryOverride(Clash.OverrideSlot.Session)
                                    session.proxyAdsBlock = it.enabled
                                    patchOverride(Clash.OverrideSlot.Session, session)
                                }
                            }
                            is ProxyDesign.Request.PatchTimeout -> {
                                scheduleClashMutation("代理测速超时") {
                                    val o = queryOverride(Clash.OverrideSlot.Session)
                                    o.proxyDelayTestTimeoutMs = it.timeoutMs
                                    patchOverride(Clash.OverrideSlot.Session, o)
                                }
                            }
                            is ProxyDesign.Request.PatchConcurrency -> {
                                scheduleClashMutation("代理测速并发") {
                                    val o = queryOverride(Clash.OverrideSlot.Session)
                                    o.proxyDelayTestConcurrency = it.concurrency
                                    patchOverride(Clash.OverrideSlot.Session, o)
                                    setHealthCheckWorkerLimit(it.concurrency)
                                }
                            }
                            ProxyDesign.Request.FlushFakeIpCache -> {
                                launch {
                                    try {
                                        withClash {
                                            closeAllConnections()
                                            flushFakeIpCache()
                                        }
                                        Log.i("[$LOG_FLUSH_FAKE_IP] Proxy page cleared DNS and Fake-IP successfully")
                                        design.showFlushFakeIpDone()
                                    } catch (e: Exception) {
                                        Log.i(
                                            "[$LOG_FLUSH_FAKE_IP] Proxy page failed to clear Fake-IP cache: ${e.message ?: e.javaClass.simpleName}",
                                            e,
                                        )
                                        design.showExceptionToast(e)
                                    }
                                }
                            }
                            ProxyDesign.Request.ClearConnectivityStats -> {
                                val result = startActivityForResult(
                                    ActivityResultContracts.StartActivityForResult(),
                                    ConnectivityStatsActivity::class.intent,
                                )
                                if (result.resultCode == RESULT_OK) {
                                    design.requests.send(ProxyDesign.Request.ReloadAll)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            unregisterReceiver(refreshReceiver)
        }
    }
}
