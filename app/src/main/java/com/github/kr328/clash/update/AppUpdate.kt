package com.github.kr328.clash.update

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 应用自更新公共入口：检查 → 下载 → 安装。
 */
object AppUpdate {
    suspend fun check(context: Context): AppUpdateCheckResult {
        return AppUpdateChecker.check(context)
    }

    suspend fun download(
        context: Context,
        remote: RemoteApk,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): File {
        return ApkDownloader.download(context, remote, onProgress)
    }

    fun canInstall(context: Context): Boolean {
        return ApkInstaller.canRequestPackageInstalls(context)
    }

    fun unknownSourcesSettingsIntent(context: Context): Intent {
        return ApkInstaller.createUnknownSourcesSettingsIntent(context)
    }

    fun installIntent(context: Context, apkFile: File): Intent {
        return ApkInstaller.createInstallIntent(context, apkFile)
    }
}
