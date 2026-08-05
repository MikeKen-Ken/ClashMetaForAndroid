package com.github.kr328.clash.update

import android.content.Context
import android.os.Build
import com.github.kr328.clash.common.compat.versionCodeCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 从自有 GitHub Release（固定 tag [RELEASE_TAG]）检查是否有新 APK。
 */
internal object AppUpdateChecker {
    const val RELEASE_TAG = "Prerelease-alpha"
    private const val OWNER = "MikeKen-Ken"
    private const val REPO = "ClashMetaForAndroid"
    private const val RELEASE_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$RELEASE_TAG"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun check(context: Context): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val localVersionName = packageInfo.versionName ?: ""
        val localVersionCode = packageInfo.versionCodeCompat
        val localUpdatedAt = packageInfo.lastUpdateTime

        val release = fetchRelease()
        val remote = pickApk(release)
            ?: throw IllegalStateException("Release 中没有可用的 APK")

        val newerByCode = remote.versionCode > localVersionCode
        val newerByTime =
            remote.versionCode == localVersionCode && remote.updatedAtMillis > localUpdatedAt + 60_000L

        if (newerByCode || newerByTime) {
            AppUpdateCheckResult.Available(localVersionName, localVersionCode, remote)
        } else {
            AppUpdateCheckResult.UpToDate(localVersionName, localVersionCode)
        }
    }

    private fun fetchRelease(): GitHubRelease {
        val request = Request.Builder()
            .url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ClashMetaForAndroid-AppUpdate")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("检查更新失败：HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw IllegalStateException("检查更新失败：空响应")
            }
            return json.decodeFromString(GitHubRelease.serializer(), body)
        }
    }

    private fun pickApk(release: GitHubRelease): RemoteApk? {
        val apkAssets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null

        val preferredAbis = Build.SUPPORTED_ABIS.toList()
        val matched = preferredAbis.firstNotNullOfOrNull { abi ->
            apkAssets.firstOrNull { asset ->
                asset.name.contains(abi, ignoreCase = true)
            }
        } ?: apkAssets.first()

        val metaFromOutput = release.assets
            .firstOrNull { it.name.equals("output-metadata.json", ignoreCase = true) }
            ?.let { fetchOutputMetadata(it.browserDownloadUrl) }
            ?.elements
            ?.firstOrNull { element ->
                element.outputFile.equals(matched.name, ignoreCase = true) ||
                    matched.name.contains(element.outputFile, ignoreCase = true)
            }

        val fallback = parseVersionFromFileName(matched.name)
        return RemoteApk(
            versionName = metaFromOutput?.versionName ?: fallback.versionName,
            versionCode = metaFromOutput?.versionCode?.toLong() ?: fallback.versionCode,
            fileName = matched.name,
            downloadUrl = matched.browserDownloadUrl,
            updatedAtMillis = parseGithubTime(matched.updatedAt),
            assetId = matched.id,
        )
    }

    private fun fetchOutputMetadata(url: String): OutputMetadata? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ClashMetaForAndroid-AppUpdate")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                json.decodeFromString(OutputMetadata.serializer(), body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVersionFromFileName(fileName: String): VersionMeta {
        // cmfa-2.11.22-alpha-arm64-v8a-release.apk → versionCode 与工程约定：2*100000+11*1000+22
        val match = Regex("""cmfa-(\d+(?:\.\d+)*)(?:-([a-zA-Z0-9]+))?""", RegexOption.IGNORE_CASE)
            .find(fileName)
        val versionName = match?.groupValues?.getOrNull(1)?.let { base ->
            val suffix = match.groupValues.getOrNull(2)
            if (suffix.isNullOrBlank()) base else "$base.${suffix.replaceFirstChar { it.uppercase() }}"
        } ?: fileName

        val versionCode = match?.groupValues?.getOrNull(1)?.let { base ->
            val parts = base.split('.').mapNotNull { it.toIntOrNull() }
            when {
                parts.size >= 3 -> parts[0] * 100000L + parts[1] * 1000L + parts[2]
                parts.size == 2 -> parts[0] * 100000L + parts[1] * 1000L
                parts.size == 1 -> parts[0] * 100000L
                else -> 0L
            }
        } ?: 0L

        return VersionMeta(versionName = versionName, versionCode = versionCode)
    }

    private fun parseGithubTime(value: String): Long {
        if (value.isBlank()) return 0L
        return try {
            val normalized = value.replace(Regex("""\.\d+Z$"""), "Z")
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            parser.parse(normalized)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private data class VersionMeta(
        val versionName: String,
        val versionCode: Long,
    )

    @Serializable
    private data class GitHubRelease(
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val id: Long,
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        @SerialName("updated_at") val updatedAt: String = "",
    )

    @Serializable
    private data class OutputMetadata(
        val elements: List<OutputElement> = emptyList(),
    )

    @Serializable
    private data class OutputElement(
        val versionCode: Int = 0,
        val versionName: String = "",
        val outputFile: String = "",
    )
}
