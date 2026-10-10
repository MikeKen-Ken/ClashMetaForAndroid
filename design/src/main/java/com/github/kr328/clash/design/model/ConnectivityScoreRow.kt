package com.github.kr328.clash.design.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConnectivityScoreRow(
    val name: String,
    val score: Double = 0.0,
    @SerialName("weightedSuccess")
    val weightedSuccess: Double = 0.0,
    @SerialName("weightedFailure")
    val weightedFailure: Double = 0.0,
    @SerialName("effectiveAvgDelayMs")
    val effectiveAvgDelayMs: Double = 0.0,
    @SerialName("hasStats")
    val hasStats: Boolean = false,
)
