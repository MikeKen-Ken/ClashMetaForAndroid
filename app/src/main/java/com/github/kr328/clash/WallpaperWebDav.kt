package com.github.kr328.clash

import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.UiBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal object WallpaperWebDav {
    private const val MAX_PACK_BYTES = 20 * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun isConfigured(store: UiStore): Boolean {
        return store.webdavUrl.isNotBlank() &&
            store.webdavUsername.isNotBlank() &&
            store.webdavPassword.isNotBlank()
    }

    suspend fun upload(store: UiStore, bytes: ByteArray) = withContext(Dispatchers.IO) {
        ensureCollection(store)
        val request = Request.Builder()
            .url(packUrl(store))
            .header("Authorization", authorization(store))
            .put(bytes.toRequestBody("application/zip".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
        }
    }

    suspend fun download(store: UiStore): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(packUrl(store))
            .header("Authorization", authorization(store))
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("empty body")
            val declared = body.contentLength()
            if (declared > MAX_PACK_BYTES) {
                throw IllegalStateException("wallpaper pack too large")
            }
            body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_PACK_BYTES) {
                        throw IllegalStateException("wallpaper pack too large")
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }
    }

    private fun ensureCollection(store: UiStore) {
        val request = Request.Builder()
            .url(collectionUrl(store))
            .header("Authorization", authorization(store))
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code != 201 && response.code != 405 && response.code != 301 &&
                response.code != 200 && response.code != 409
            ) {
                // 目录可能已存在；其余错误在 PUT 时再暴露
            }
        }
    }

    private fun packUrl(store: UiStore): String {
        return "${collectionUrl(store).trimEnd('/')}/${UiBackground.PACK_FILE_NAME}"
    }

    private fun collectionUrl(store: UiStore): String {
        val base = requireHttpsWebDavUrl(store.webdavUrl)
        return "$base/${UiBackground.REMOTE_DIR}"
    }

    private fun requireHttpsWebDavUrl(raw: String): String {
        val base = raw.trim().trimEnd('/')
        if (!base.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("WebDAV URL must be https")
        }
        return base
    }

    private fun authorization(store: UiStore): String {
        return Credentials.basic(store.webdavUsername.trim(), store.webdavPassword)
    }
}
