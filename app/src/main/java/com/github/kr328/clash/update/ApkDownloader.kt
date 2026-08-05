package com.github.kr328.clash.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 将远端 APK 下载到应用缓存目录。
 */
internal object ApkDownloader {
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
        val request = Request.Builder()
            .url(remote.downloadUrl)
            .header("User-Agent", "ClashMetaForAndroid-AppUpdate")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败：HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("下载失败：空响应体")
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

        if (!target.exists() || target.length() <= 0L) {
            throw IllegalStateException("下载失败：文件无效")
        }
        target
    }
}
