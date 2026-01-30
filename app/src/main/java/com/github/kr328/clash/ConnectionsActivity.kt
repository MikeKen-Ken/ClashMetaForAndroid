package com.github.kr328.clash

import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.ConnectionsDesign
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

class ConnectionsActivity : BaseActivity<ConnectionsDesign>() {
    override suspend fun main() {
        val design = ConnectionsDesign(this)
        setContentDesign(design)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> launch { refreshConnections(design) }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        is ConnectionsDesign.Request.CloseConnection -> {
                            withClash { Clash.closeConnection(it.connection.id) }
                            launch { refreshConnections(design) }
                        }
                    }
                }
                ticker.onReceive {
                    launch { refreshConnections(design) }
                }
            }
        }
    }

    private suspend fun refreshConnections(design: ConnectionsDesign) {
        val snapshot = withClash { Clash.queryConnections() }
        design.patchConnections(snapshot)
    }
}
