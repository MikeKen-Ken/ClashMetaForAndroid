package com.github.kr328.clash.design.connections

import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.adapter.ConnectionDisplayItem
import com.github.kr328.clash.design.util.formatConnectionStartTime
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.design.util.toSpeedString

/** Keeps sampling separate from formatting so hidden tabs do not build active rows. */
class ActiveConnectionRows {
    private var connections: List<Connection> = emptyList()
    private var previousDownloads: Map<String, Long> = emptyMap()
    private var speeds: Map<String, Long> = emptyMap()
    private var sampledAt = 0L
    private val startLabels = linkedMapOf<String, String>()

    fun update(next: List<Connection>, nowMs: Long) {
        val elapsed = if (sampledAt > 0) (nowMs - sampledAt) / 1000.0 else 1.0
        val seconds = if (elapsed < 0.5) 1.0 else elapsed
        speeds = next.associate { conn ->
            val previous = previousDownloads[conn.id]
            conn.id to if (previous == null) 0L else
                ((conn.download - previous) / seconds).toLong().coerceAtLeast(0)
        }
        connections = next
        previousDownloads = next.associate { it.id to it.download }
        sampledAt = nowMs
    }

    fun build(newestFirst: Boolean): List<ConnectionDisplayItem> {
        val sorted = connections.sortedBy { it.start }
        val ordered = if (newestFirst) sorted.asReversed() else sorted
        return ordered.map { conn ->
            val start = startLabels[conn.start] ?: conn.start.formatConnectionStartTime().also {
                if (startLabels.size >= 512) startLabels.remove(startLabels.keys.first())
                startLabels[conn.start] = it
            }
            ConnectionDisplayItem(
                conn,
                "↓ ${conn.download.toBytesString()} (${(speeds[conn.id] ?: 0L).toSpeedString()})",
                start,
                isClosed = false,
            )
        }
    }
}
