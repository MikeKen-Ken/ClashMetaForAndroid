package com.github.kr328.clash.update

/**
 * 远端可安装的 APK 信息。
 */
data class RemoteApk(
    val versionName: String,
    val versionCode: Long,
    val fileName: String,
    val downloadUrl: String,
    val updatedAtMillis: Long,
    val assetId: Long,
)

/**
 * 检查应用更新的结果。
 */
sealed class AppUpdateCheckResult {
    data class UpToDate(
        val currentVersionName: String,
        val currentVersionCode: Long,
    ) : AppUpdateCheckResult()

    data class Available(
        val currentVersionName: String,
        val currentVersionCode: Long,
        val remote: RemoteApk,
    ) : AppUpdateCheckResult()
}
