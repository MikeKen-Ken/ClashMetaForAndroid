package com.github.kr328.clash.design.connections

import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.adapter.ClosedEntry

private val REJECT_CHAIN_LEAFS = setOf("REJECT", "REJECT-DROP", "PASS")

/** 是否为 REJECT / REJECT-DROP / PASS 出站 */
fun Connection.isRejectOutbound(): Boolean {
    val leaf = chains.substringBefore('[').trim()
    return leaf in REJECT_CHAIN_LEAFS
}

/**
 * REJECT 去重键：相同目标（host → sourceIP → 进程名）+ 相同规则原因（rule / payload / detail）。
 * 三者皆无时不 dedupe。
 */
fun Connection.rejectDedupeKey(): String? {
    if (!isRejectOutbound()) return null
    val target = metadata?.host?.takeIf { it.isNotBlank() }
        ?: metadata?.sourceIP?.takeIf { it.isNotBlank() }
        ?: metadata?.process?.takeIf { it.isNotBlank() }
        ?: return null
    return "$target|$rule|$rulePayload|$ruleDetail"
}

/**
 * 写入已关闭列表：REJECT 按 [rejectDedupeKey] 只保留 [closedAt] 最新的一条；其它连接仍按 id 追加。
 * @return 列表是否发生变化
 */
fun MutableList<ClosedEntry>.upsertClosedEntry(entry: ClosedEntry): Boolean {
    val dedupeKey = entry.connection.rejectDedupeKey()
    if (dedupeKey == null) {
        add(entry)
        return true
    }
    val index = indexOfFirst { it.connection.rejectDedupeKey() == dedupeKey }
    if (index < 0) {
        add(entry)
        return true
    }
    val existing = this[index]
    if (entry.closedAt >= existing.closedAt) {
        this[index] = entry
        return true
    }
    return false
}

/** 从磁盘恢复后压实 REJECT 重复项 */
fun List<ClosedEntry>.compactRejectClosedEntries(): List<ClosedEntry> {
    val out = mutableListOf<ClosedEntry>()
    for (entry in this) {
        out.upsertClosedEntry(entry)
    }
    return out
}
