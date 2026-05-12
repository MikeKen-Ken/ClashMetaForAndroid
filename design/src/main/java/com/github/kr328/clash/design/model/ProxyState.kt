package com.github.kr328.clash.design.model

data class ProxyState(
    var now: String,
    var nowIsManual: Boolean = false,
    var connectTimes: Int = 0,
    var maxConnectTimes: Int = 0,
)
