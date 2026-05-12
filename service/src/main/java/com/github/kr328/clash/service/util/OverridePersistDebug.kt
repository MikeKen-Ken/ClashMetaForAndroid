package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ConfigurationOverride

/** 单行摘要，供主界面调试页转发显示（避免整段 JSON）。 */
fun ConfigurationOverride.persistSummaryForDebug(): String =
    "allowLan=$allowLan strictRoute=${tun.strictRoute} logLevel=$logLevel " +
        "bindLen=${bindAddress?.length ?: 0} proxySel=${proxySelections?.size ?: 0}"
