package com.github.kr328.clash.common

import kotlinx.coroutines.channels.Channel

/**
 * 当 core 侧某代理组触发健康检查/节点变更（如 Fallback 自动切换）时，
 * 通过此 Channel 通知代理界面刷新该组，避免定时全量刷新带来的性能问题。
 */
object ProxyGroupRefresh {
    val channel = Channel<String>(Channel.UNLIMITED)

    fun notifyGroupChanged(groupName: String) {
        channel.trySend(groupName)
    }
}
