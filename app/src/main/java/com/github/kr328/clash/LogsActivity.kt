package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setFileName
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.LogsDesign
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import com.github.kr328.clash.util.scheduleClashMutation
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class LogsActivity : BaseActivity<LogsDesign>() {

    override suspend fun main() {
        val initialLogLevel = try {
            withClash {
                queryOverride(Clash.OverrideSlot.Session).logLevel
                    ?: queryOverride(Clash.OverrideSlot.Persist).logLevel
                    ?: LogMessage.Level.Info
            }
        } catch (e: Exception) {
            Log.e("读取日志级别失败: ${e.message}", e)
            LogMessage.Level.Info
        }
        val design = LogsDesign(this, initialLogLevel)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            val files = withContext(Dispatchers.IO) {
                                loadFiles()
                            }

                            design.patchLogs(files)
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        is LogsDesign.Request.ChangeLogLevel -> {
                            val level = it.level
                            scheduleClashMutation("日志") {
                                val session = queryOverride(Clash.OverrideSlot.Session)
                                session.logLevel = level
                                patchOverride(Clash.OverrideSlot.Session, session)
                                val persist = queryOverride(Clash.OverrideSlot.Persist)
                                persist.logLevel = level
                                patchOverride(Clash.OverrideSlot.Persist, persist)
                            }
                        }
                        LogsDesign.Request.StartLogcat -> {
                            startActivity(LogcatActivity::class.intent)
                            finish()
                        }
                        LogsDesign.Request.DeleteAll -> {
                            if (design.requestDeleteAll()) {
                                withContext(Dispatchers.IO) {
                                    deleteAllLogs()
                                }

                                events.trySend(Event.ActivityStart)
                            }
                        }
                        is LogsDesign.Request.OpenFile -> {
                            startActivity(LogcatActivity::class.intent.setFileName(it.file.fileName))
                        }
                    }
                }
            }
        }
    }

    private fun loadFiles(): List<LogFile> {
        val list = cacheDir.resolve("logs").listFiles()?.toList() ?: emptyList()

        return list.mapNotNull { LogFile.parseFromFileName(it.name) }
    }

    private fun deleteAllLogs() {
        logsDir.deleteRecursively()
    }
}