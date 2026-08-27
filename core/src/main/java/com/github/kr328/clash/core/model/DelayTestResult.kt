package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DelayTestResult(
    val group: String,
    val tested: Int,
    val succeeded: Int,
    val failed: Int,
    val elapsedMs: Long,
    val error: String? = null,
) {
    val hasSuccess: Boolean
        get() = succeeded > 0
}
