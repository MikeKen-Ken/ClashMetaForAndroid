package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionsSnapshot(
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val connections: List<Connection> = emptyList(),
)

@Serializable
data class Connection(
    val id: String,
    val metadata: ConnectionMetadata? = null,
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = "",
    val chains: String = "",
    @SerialName("providerChains") val providerChains: String = "",
    val rule: String = "",
    val rulePayload: String = "",
    val ruleDetail: String = "",
)

@Serializable
data class ConnectionMetadata(
    val network: String = "",
    val host: String = "",
    val process: String = "",
    @SerialName("sourceIP") val sourceIP: String = "",
    @SerialName("destinationPort") val destinationPort: String = "",
)
