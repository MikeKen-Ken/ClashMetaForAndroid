package com.github.kr328.clash

import com.github.kr328.clash.common.log.DebugLog
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.DebugDesign
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

class DebugActivity : BaseActivity<DebugDesign>() {

    override suspend fun main() {
        val design = DebugDesign(this)
        setContentDesign(design)

        design.patchEntries(DebugLog.getEntries())
        val refreshTicker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                refreshTicker.onReceive {
                    design.patchEntries(DebugLog.getEntries())
                }
                design.requests.onReceive {
                    when (it) {
                        DebugDesign.Request.Clear -> design.clearEntries()
                    }
                }
            }
        }
    }
}
