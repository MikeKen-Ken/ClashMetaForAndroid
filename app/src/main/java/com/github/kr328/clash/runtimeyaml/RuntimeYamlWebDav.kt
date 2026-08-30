package com.github.kr328.clash.runtimeyaml

import com.github.kr328.clash.design.store.UiStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal object RuntimeYamlWebDav {
    const val MAX_RUNTIME_YAML_BYTES = 10 * 1024 * 1024
    const val REMOTE_DIR = "clash-runtime-yaml"
    const val FILE_NAME = "runtime.yaml"

    private val yamlMediaType = "application/yaml".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun hasCredentials(store: UiStore): Boolean {
        return store.webdavUrl.isNotBlank() &&
            store.webdavUsername.isNotBlank() &&
            store.webdavPassword.isNotBlank()
    }

    fun isConfigured(store: UiStore): Boolean {
        return hasCredentials(store) &&
            store.webdavUrl.trim().startsWith("https://", ignoreCase = true)
    }

    suspend fun upload(store: UiStore, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Runtime YAML is empty" }
        require(bytes.size <= MAX_RUNTIME_YAML_BYTES) {
            "Runtime YAML is larger than 10 MB"
        }
        ensureCollection(store)
        val request = Request.Builder()
            .url(objectUrl(store))
            .header("Authorization", authorization(store))
            .put(bytes.toRequestBody(yamlMediaType))
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
        }
    }

    suspend fun download(store: UiStore): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(objectUrl(store))
            .header("Authorization", authorization(store))
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("Empty WebDAV response")
            val declared = body.contentLength()
            if (declared > MAX_RUNTIME_YAML_BYTES) {
                error("Runtime YAML is larger than 10 MB")
            }
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_RUNTIME_YAML_BYTES) {
                        "Runtime YAML is larger than 10 MB"
                    }
                    output.write(buffer, 0, count)
                }
                check(total > 0) { "Runtime YAML is empty" }
                output.toByteArray()
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

    private fun objectUrl(store: UiStore): String {
        return "${collectionUrl(store).trimEnd('/')}/$FILE_NAME"
    }

    private fun collectionUrl(store: UiStore): String {
        return "${requireHttpsWebDavUrl(store.webdavUrl)}/$REMOTE_DIR"
    }

    private fun requireHttpsWebDavUrl(raw: String): String {
        val base = raw.trim().trimEnd('/')
        require(base.startsWith("https://", ignoreCase = true)) {
            "WebDAV URL must be https"
        }
        return base
    }

    private fun authorization(store: UiStore): String {
        return Credentials.basic(store.webdavUsername.trim(), store.webdavPassword)
    }
}
