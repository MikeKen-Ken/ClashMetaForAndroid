package com.github.kr328.clash.design.adapter

import com.github.kr328.clash.core.model.Connection

/**
 * Connection with precomputed traffic display text (download/upload amount and speed)
 * and optional start time display text.
 */
data class ConnectionDisplayItem(
    val connection: Connection,
    val trafficDisplayText: String,
    val startTimeDisplayText: String = "",
)
