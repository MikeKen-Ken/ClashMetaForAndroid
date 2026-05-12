package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.DebugLog
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.DebugDesign
import com.github.kr328.clash.design.ui.ToastDuration
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class DebugActivity : BaseActivity<DebugDesign>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

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
                        DebugDesign.Request.Copy -> copyEntriesToClipboard(design)
                    }
                }
            }
        }
    }

    private suspend fun copyEntriesToClipboard(design: DebugDesign) {
        val entries = DebugLog.getEntries()
        val text = entries.joinToString("\n") { e ->
            "[${timeFormat.format(Date(e.timeMillis))}] [${e.tag}] ${e.message}"
        }
        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("debug_log", text))
        design.showToast(com.github.kr328.clash.design.R.string.copied, ToastDuration.Short)
    }
}
