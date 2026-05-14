package com.github.kr328.clash

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.compat.versionCodeCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.AppCrashedDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.log.SystemLogcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.github.kr328.clash.design.R

class AppCrashedActivity : BaseActivity<AppCrashedDesign>() {
    override suspend fun main() {
        val design = AppCrashedDesign(this)

        setContentDesign(design)

        val packageInfo = withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0)
        }

        Log.i("App version: versionName = ${packageInfo.versionName} versionCode = ${packageInfo.versionCodeCompat}")

        val logs = withContext(Dispatchers.IO) {
            SystemLogcat.dumpCrash()
        }

        design.setAppLogs(logs)

        val defaultExportName = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date())
            .let { "crash-$it.txt" }

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { req ->
                    when (req) {
                        AppCrashedDesign.Request.Export -> {
                            val output = startActivityForResult(
                                ActivityResultContracts.CreateDocument("text/plain"),
                                defaultExportName,
                            )
                            if (output != null) {
                                try {
                                    withContext(Dispatchers.IO) {
                                        writeCrashLog(output, logs)
                                    }
                                    design.showToast(R.string.file_exported, ToastDuration.Long)
                                } catch (e: Exception) {
                                    design.showExceptionToast(e)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun writeCrashLog(uri: Uri, text: String) {
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(text)
        } ?: error("无法打开导出目标")
    }
}