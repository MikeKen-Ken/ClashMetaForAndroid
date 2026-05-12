package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProvidersDesign
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.scheduleCriticalWork
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProvidersActivity : BaseActivity<ProvidersDesign>() {
    override suspend fun main() {
        val providers = withClash { queryProviders().sorted() }
        val design = ProvidersDesign(this, providers)

        setContentDesign(design)

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded -> {
                            val newList = withClash { queryProviders().sorted() }

                            if (newList != providers) {
                                startActivity(ProvidersActivity::class.intent)

                                finish()
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        is ProvidersDesign.Request.Update -> {
                            val index = it.index
                            val provider = it.provider
                            scheduleCriticalWork("订阅更新") {
                                try {
                                    withClash {
                                        updateProvider(provider.type, provider.name)
                                    }

                                    withContext(Dispatchers.Main) {
                                        design.notifyChanged(index)
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        design.showExceptionToast(
                                            getString(
                                                R.string.format_update_provider_failure,
                                                provider.name,
                                                e.message
                                            )
                                        )

                                        design.notifyUpdated(index)
                                    }
                                }
                            }
                        }
                    }
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }
}