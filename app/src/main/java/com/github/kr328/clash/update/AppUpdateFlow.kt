package com.github.kr328.clash.update

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 应用内「检查更新」交互流程（检查 → 确认 → 下载 → 安装）。
 */
object AppUpdateFlow {
    @Volatile
    private var inProgress: Boolean = false

    suspend fun run(
        activity: AppCompatActivity,
        showToast: suspend (text: CharSequence, duration: ToastDuration) -> Unit,
    ) {
        if (inProgress) return
        inProgress = true
        try {
            showToast(activity.getText(R.string.app_update_checking), ToastDuration.Short)

            val result = withContext(Dispatchers.IO) {
                AppUpdate.check(activity)
            }

            when (result) {
                is AppUpdateCheckResult.UpToDate -> {
                    showToast(activity.getText(R.string.app_update_latest), ToastDuration.Long)
                }
                is AppUpdateCheckResult.Available -> {
                    val confirmed = confirmDownload(activity, result)
                    if (!confirmed) return

                    if (!AppUpdate.canInstall(activity)) {
                        showToast(
                            activity.getText(R.string.app_update_need_permission),
                            ToastDuration.Long,
                        )
                        activity.startActivity(AppUpdate.unknownSourcesSettingsIntent(activity))
                        return
                    }

                    showToast(
                        activity.getText(R.string.app_update_downloading),
                        ToastDuration.Long,
                    )
                    val apk = withContext(Dispatchers.IO) {
                        AppUpdate.download(activity, result.remote)
                    }
                    activity.startActivity(AppUpdate.installIntent(activity, apk))
                }
            }
        } catch (e: Exception) {
            showToast(
                activity.getString(
                    R.string.app_update_failed,
                    e.message ?: e.javaClass.simpleName,
                ),
                ToastDuration.Long,
            )
        } finally {
            inProgress = false
        }
    }

    private suspend fun confirmDownload(
        activity: AppCompatActivity,
        result: AppUpdateCheckResult.Available,
    ): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(R.string.app_update_available_title)
                    .setMessage(
                        activity.getString(
                            R.string.app_update_available_message,
                            result.currentVersionName,
                            result.remote.versionName,
                        ),
                    )
                    .setPositiveButton(R.string.app_update_download_install) { _, _ ->
                        if (cont.isActive) cont.resume(true)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        if (cont.isActive) cont.resume(false)
                    }
                    .setOnCancelListener {
                        if (cont.isActive) cont.resume(false)
                    }
                    .create()

                cont.invokeOnCancellation {
                    dialog.dismiss()
                }
                dialog.show()
            }
        }
    }
}
