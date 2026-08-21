package com.github.kr328.clash.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.github.kr328.clash.common.compat.versionCodeCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 从自有 GitHub Release（固定 tag [RELEASE_TAG]）检查是否有新 APK。
 *
 * 优先通过 Release 资产直链拉取 `output-metadata.json`（直连 + 镜像），
 * 避免未认证 GitHub API 的 403 rate limit；API 仅作可选回退。
 *
 * HTTP 404 通常表示远端 GitHub Actions（Build Pre-Release）失败：
 * 工作流会先删除旧的 [RELEASE_TAG] Release，再上传新资产；若中途失败，
 * 清单与 APK 都不存在。
 */
internal object AppUpdateChecker {
    const val RELEASE_TAG = "Prerelease-alpha"
    private const val TAG = "AppUpdateChecker"
    private const val OWNER = "MikeKen-Ken"
    private const val REPO = "ClashMetaForAndroid"
    private const val USER_AGENT = "ClashMetaForAndroid-AppUpdate"

    private const val RELEASE_DOWNLOAD_BASE =
        "https://github.com/$OWNER/$REPO/releases/download/$RELEASE_TAG"
    private const val RELEASE_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$RELEASE_TAG"

    /** 直连优先，镜像用于绕过 GitHub API/直连限流或网络阻断 */
    private val METADATA_URLS = listOf(
        "$RELEASE_DOWNLOAD_BASE/output-metadata.json",
        "https://gh-proxy.com/$RELEASE_DOWNLOAD_BASE/output-metadata.json",
        "https://mirror.ghproxy.com/$RELEASE_DOWNLOAD_BASE/output-metadata.json",
    )

    private val API_URLS = listOf(
        RELEASE_API,
        "https://gh-proxy.com/$RELEASE_API",
        "https://mirror.ghproxy.com/$RELEASE_API",
    )

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

        Log.i(
            TAG,
            "开始检查更新：本地 versionName=$localVersionName versionCode=$localVersionCode",
        )

        val remote = resolveRemoteApk()
            ?: throw AppUpdateCheckException.NoApk("No APK matching the current ABI was found in the Release")

        Log.i(
            TAG,
            "远端 APK：${remote.fileName} versionName=${remote.versionName} " +
                "versionCode=${remote.versionCode}",
        )

        val newerByCode = remote.versionCode > localVersionCode
        val newerByTime =
            remote.versionCode == localVersionCode && remote.updatedAtMillis > localUpdatedAt + 60_000L

        Log.i(
            TAG,
            "版本比较：newerByCode=$newerByCode newerByTime=$newerByTime " +
                "localUpdatedAt=$localUpdatedAt remoteUpdatedAt=${remote.updatedAtMillis}",
        )

        if (newerByCode || newerByTime) {
            AppUpdateCheckResult.Available(localVersionName, localVersionCode, remote)
        } else {
            AppUpdateCheckResult.UpToDate(localVersionName, localVersionCode)
        }
    }

    /**
     * 优先读 output-metadata.json；失败再尝试 GitHub API（含代理）。
     */
    private fun resolveRemoteApk(): RemoteApk? {
        val metadataErrors = mutableListOf<SourceFailure>()
        for (url in METADATA_URLS) {
            try {
                Log.i(TAG, "尝试拉取 Release 元数据：$url")
                val fetched = fetchOutputMetadataWithHeaders(url)
                val remote = pickApkFromMetadata(fetched)
                if (remote != null) {
                    val updatedAt = probeAssetUpdatedAt(remote.fileName)
                        .takeIf { it > 0L }
                        ?: remote.updatedAtMillis
                    Log.i(TAG, "已通过 Release 资产直链完成检查 updatedAt=$updatedAt")
                    return remote.copy(updatedAtMillis = updatedAt)
                }
                metadataErrors.add(SourceFailure("$url → 元数据中无可用 APK"))
            } catch (e: AppUpdateCheckException) {
                metadataErrors.add(sourceFailureFrom(e))
                Log.i(TAG, "元数据源失败，尝试下一源：${e.message}")
            } catch (e: IOException) {
                metadataErrors.add(SourceFailure("网络失败（$url）：${e.message ?: e.javaClass.simpleName}"))
                Log.i(TAG, "元数据网络失败，尝试下一源", e)
            } catch (e: Exception) {
                metadataErrors.add(SourceFailure("解析失败（$url）：${e.message ?: e.javaClass.simpleName}"))
                Log.i(TAG, "元数据解析失败，尝试下一源", e)
            }
        }

        Log.i(TAG, "Release 资产直链均失败，回退 GitHub API")
        val apiErrors = mutableListOf<SourceFailure>()
        for (url in API_URLS) {
            try {
                Log.i(TAG, "尝试 GitHub API：$url")
                val release = fetchReleaseApi(url)
                val remote = pickApkFromApiAssets(release)
                if (remote != null) {
                    Log.i(TAG, "已通过 GitHub API 完成检查")
                    return remote
                }
                apiErrors.add(SourceFailure("$url → API 响应中无可用 APK"))
            } catch (e: AppUpdateCheckException) {
                apiErrors.add(sourceFailureFrom(e))
                Log.i(TAG, "API 源失败，尝试下一源：${e.message}")
            } catch (e: IOException) {
                apiErrors.add(SourceFailure("网络失败（$url）：${e.message ?: e.javaClass.simpleName}"))
                Log.i(TAG, "API 网络失败，尝试下一源", e)
            } catch (e: Exception) {
                apiErrors.add(SourceFailure("解析失败（$url）：${e.message ?: e.javaClass.simpleName}"))
                Log.i(TAG, "API 解析失败，尝试下一源", e)
            }
        }

        throwAllSourcesFailed(metadataErrors, apiErrors)
    }

    /**
     * 全源失败时的用户可见说明。
     * 若各源均为 HTTP 404，通常是工作流先删掉旧 [RELEASE_TAG] 后未能重新上传资产。
     */
    private fun throwAllSourcesFailed(
        metadataErrors: List<SourceFailure>,
        apiErrors: List<SourceFailure>,
    ): Nothing {
        val all = metadataErrors + apiErrors
        val onlyNotFound = all.isNotEmpty() && all.all { it.httpCode == 404 }
        val message = if (onlyNotFound) {
            "Manifest update failed (HTTP 404): the remote GitHub Actions (Build Pre-Release) " +
                "workflow may have failed to build or publish; output-metadata.json is missing " +
                "from Release “$RELEASE_TAG”. Check the repository Actions page."
        } else {
            "Update check failed: all sources are unavailable. " + all.joinToString("; ") { it.detail }
        }
        throw AppUpdateCheckException.AllSourcesFailed(message)
    }

    private fun pickApkFromMetadata(fetched: FetchedMetadata): RemoteApk? {
        val metadata = fetched.metadata
        val elements = metadata.elements.filter { it.outputFile.endsWith(".apk", ignoreCase = true) }
        if (elements.isEmpty()) return null

        val preferredAbis = Build.SUPPORTED_ABIS.toList()
        val matched = preferredAbis.firstNotNullOfOrNull { abi ->
            elements.firstOrNull { element ->
                element.outputFile.contains(abi, ignoreCase = true) ||
                    element.filters.any {
                        it.filterType.equals("ABI", ignoreCase = true) &&
                            it.value.equals(abi, ignoreCase = true)
                    }
            }
        } ?: elements.first()

        val fileName = matched.outputFile
        val fallback = parseVersionFromFileName(fileName)
        return RemoteApk(
            versionName = matched.versionName.ifBlank { fallback.versionName },
            versionCode = matched.versionCode.takeIf { it > 0 }?.toLong() ?: fallback.versionCode,
            fileName = fileName,
            downloadUrl = buildDownloadUrl(fileName),
            updatedAtMillis = fetched.lastModifiedMillis,
            assetId = 0L,
        )
    }

    /** 通过 HEAD 获取远端 APK 的 Last-Modified，用于同 versionCode 重建包检测。 */
    private fun probeAssetUpdatedAt(fileName: String): Long {
        for (url in mirrorDownloadUrls(fileName)) {
            val updatedAt = headLastModified(url)
            if (updatedAt > 0L) {
                Log.i(TAG, "远端 APK 更新时间：$updatedAt（$url）")
                return updatedAt
            }
        }
        return 0L
    }

    private fun headLastModified(url: String): Long {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")
            .head()
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0L
                parseHttpLastModified(response.header("Last-Modified").orEmpty())
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun pickApkFromApiAssets(release: GitHubRelease): RemoteApk? {
        val apkAssets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null

        val preferredAbis = Build.SUPPORTED_ABIS.toList()
        val matched = preferredAbis.firstNotNullOfOrNull { abi ->
            apkAssets.firstOrNull { asset ->
                asset.name.contains(abi, ignoreCase = true)
            }
        } ?: apkAssets.first()

        val metaElement = release.assets
            .firstOrNull { it.name.equals("output-metadata.json", ignoreCase = true) }
            ?.let { asset ->
                fetchOutputMetadataOptional(asset.browserDownloadUrl)
            }
            ?.elements
            ?.firstOrNull { element ->
                element.outputFile.equals(matched.name, ignoreCase = true) ||
                    matched.name.contains(element.outputFile, ignoreCase = true)
            }

        val fallback = parseVersionFromFileName(matched.name)
        return RemoteApk(
            versionName = metaElement?.versionName?.takeIf { it.isNotBlank() } ?: fallback.versionName,
            versionCode = metaElement?.versionCode?.takeIf { it > 0 }?.toLong() ?: fallback.versionCode,
            fileName = matched.name,
            downloadUrl = matched.browserDownloadUrl.ifBlank { buildDownloadUrl(matched.name) },
            updatedAtMillis = parseGithubTime(matched.updatedAt),
            assetId = matched.id,
        )
    }

    private fun buildDownloadUrl(fileName: String): String {
        return "$RELEASE_DOWNLOAD_BASE/$fileName"
    }

    /** 供下载器在直连失败时尝试的镜像地址 */
    fun mirrorDownloadUrls(fileName: String): List<String> {
        val direct = buildDownloadUrl(fileName)
        return listOf(
            direct,
            "https://gh-proxy.com/$direct",
            "https://mirror.ghproxy.com/$direct",
        ).distinct()
    }

    private fun fetchOutputMetadataWithHeaders(url: String): FetchedMetadata {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val code = response.code
                if (!response.isSuccessful) {
                    throwHttpFailure(code, url, isManifest = true)
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    throw AppUpdateCheckException.HttpError("Update check failed: empty response ($url)", code)
                }
                return FetchedMetadata(
                    metadata = json.decodeFromString(OutputMetadata.serializer(), body),
                    lastModifiedMillis = parseHttpLastModified(response.header("Last-Modified").orEmpty()),
                )
            }
        } catch (e: AppUpdateCheckException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw AppUpdateCheckException.HttpError(
                "Update check failed: ${e.message ?: e.javaClass.simpleName} ($url)",
                -1,
            )
        }
    }

    private fun fetchOutputMetadataOptional(primaryUrl: String): OutputMetadata? {
        val candidates = buildList {
            add(primaryUrl)
            if (primaryUrl.startsWith("https://github.com/")) {
                add("https://gh-proxy.com/$primaryUrl")
                add("https://mirror.ghproxy.com/$primaryUrl")
            }
        }.distinct()
        for (url in candidates) {
            try {
                return fetchJson(url, OutputMetadata.serializer())
            } catch (_: Exception) {
                // 可选增强，失败则继续
            }
        }
        return null
    }

    private fun fetchReleaseApi(url: String): GitHubRelease {
        return fetchJson(
            url,
            GitHubRelease.serializer(),
            extraHeaders = mapOf("Accept" to "application/vnd.github+json"),
        )
    }

    private fun <T> fetchJson(
        url: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        extraHeaders: Map<String, String> = emptyMap(),
    ): T {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val code = response.code
                if (!response.isSuccessful) {
                    throwHttpFailure(code, url, isManifest = false)
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    throw AppUpdateCheckException.HttpError("Update check failed: empty response ($url)", code)
                }
                return json.decodeFromString(deserializer, body)
            }
        } catch (e: AppUpdateCheckException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw AppUpdateCheckException.HttpError(
                "Update check failed: ${e.message ?: e.javaClass.simpleName} ($url)",
                -1,
            )
        }
    }

    private fun throwHttpFailure(code: Int, url: String, isManifest: Boolean): Nothing {
        throw when (code) {
            404 -> AppUpdateCheckException.HttpError(
                if (isManifest) {
                    "Manifest update failed (HTTP 404): the remote GitHub Actions (Build Pre-Release) " +
                        "workflow did not publish successfully; output-metadata.json is missing " +
                        "under tag $RELEASE_TAG ($url)"
                } else {
                    "Release does not exist (HTTP 404): the remote GitHub Actions workflow may have " +
                        "deleted the old tag without republishing $RELEASE_TAG ($url)"
                },
                code,
            )
            403 -> AppUpdateCheckException.Forbidden(
                "Update check rejected (HTTP 403; GitHub rate limit may have been triggered): $url",
            )
            else -> AppUpdateCheckException.HttpError(
                "Update check failed: HTTP $code ($url)",
                code,
            )
        }
    }

    private fun sourceFailureFrom(e: AppUpdateCheckException): SourceFailure {
        val code = (e as? AppUpdateCheckException.HttpError)?.code
        return SourceFailure(e.message ?: e.javaClass.simpleName, code)
    }

    private fun parseVersionFromFileName(fileName: String): VersionMeta {
        // cmfa-2.11.22-alpha-arm64-v8a-release.apk → versionCode：2*100000+11*1000+22
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

    private fun parseHttpLastModified(value: String): Long {
        if (value.isBlank()) return 0L
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val parsed = parser.parse(value)?.time
                if (parsed != null) return parsed
            } catch (_: Exception) {
                // 尝试下一种格式
            }
        }
        return 0L
    }

    private data class SourceFailure(
        val detail: String,
        val httpCode: Int? = null,
    )

    private data class FetchedMetadata(
        val metadata: OutputMetadata,
        val lastModifiedMillis: Long,
    )

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
        val id: Long = 0L,
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
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
        val filters: List<OutputFilter> = emptyList(),
    )

    @Serializable
    private data class OutputFilter(
        val filterType: String = "",
        val value: String = "",
    )
}

/**
 * 检查更新过程中的可区分错误（网络 / 403 / 无 APK / 全源失败）。
 */
internal sealed class AppUpdateCheckException(message: String) : IllegalStateException(message) {
    class Forbidden(message: String) : AppUpdateCheckException(message)
    class HttpError(message: String, val code: Int) : AppUpdateCheckException(message)
    class NoApk(message: String) : AppUpdateCheckException(message)
    class AllSourcesFailed(message: String) : AppUpdateCheckException(message)
}