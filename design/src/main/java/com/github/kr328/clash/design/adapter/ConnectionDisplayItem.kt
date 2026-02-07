package com.github.kr328.clash.design.adapter

import com.github.kr328.clash.core.model.Connection

/**
 * Connection with precomputed traffic display text (download/upload amount and speed)
 * and optional start time display text.
 * @param isClosed when true, item is from closed list (e.g. hide close button)
 */
data class ConnectionDisplayItem(
    val connection: Connection,
    val trafficDisplayText: String,
    val startTimeDisplayText: String = "",
    val isClosed: Boolean = false,
)

/** Closed connection with timestamp for retention filtering */
data class ClosedEntry(
    val connection: Connection,
    val closedAt: Long,
)
