package com.github.kr328.clash.design.model

import kotlinx.serialization.Serializable

@Serializable
data class ConnectivityPulledDevice(
    val device: String,
    val updatedAt: Long,
    val proxyCount: Int,
)

@Serializable
data class ConnectivityMergeStatus(
    val phase: Phase = Phase.Idle,
    val progress: Int = 0,
    val finishedAt: Long = 0,
    val error: String? = null,
    val pulled: List<ConnectivityPulledDevice> = emptyList(),
    val skipped: List<String> = emptyList(),
) {
    enum class Phase { Idle, Running, Success, Partial, Failed }
}
