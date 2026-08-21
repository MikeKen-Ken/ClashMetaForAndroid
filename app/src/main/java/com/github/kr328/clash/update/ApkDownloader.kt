package com.github.kr328.clash.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 将远端 APK 下载到应用缓存目录。
 * 直连失败时按镜像列表回退。
 */
internal object ApkDownloader {
    private const val TAG = "ApkDownloader"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    suspend fun download(
        context: Context,
        remote: RemoteApk,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "app-update").apply {
            if (!exists()) mkdirs()
        }
        dir.listFiles()?.forEach { it.delete() }

        val target = File(dir, remote.fileName)
        val urls = buildList {
            add(remote.downloadUrl)
            addAll(AppUpdateChecker.mirrorDownloadUrls(remote.fileName))
        }.distinct()

        var lastError: Exception? = null
        for (url in urls) {
            try {
                Log.i(TAG, "Starting APK download: $url")
                downloadToFile(url, target, onProgress)
                if (target.exists() && target.length() > 0L) {
                    Log.i(TAG, "Download completed: ${target.length()} bytes")
                    return@withContext target
                }
                lastError = IllegalStateException("Download failed: invalid file ($url)")
            } catch (e: Exception) {
                lastError = e
                Log.i(TAG, "Download failed; trying next source: ${e.message}")
                if (target.exists()) target.delete()
            }
        }

        throw lastError ?: IllegalStateException("Download failed: no available download source")
    }

    private fun downloadToFile(
        url: String,
        target: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)?,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ClashMetaForAndroid-AppUpdate")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val code = response.code
            if (!response.isSuccessful) {
                val msg = when (code) {
                    403 -> "Download rejected (HTTP 403): $url"
                    else -> "Download failed: HTTP $code ($url)"
                }
                throw IllegalStateException(msg)
            }
            val body = response.body ?: throw IllegalStateException("Download failed: empty response body ($url)")
            val total = body.contentLength()
            var downloaded = 0L

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, total)
                    }
                    output.flush()
                }
            }
        }
    }
}