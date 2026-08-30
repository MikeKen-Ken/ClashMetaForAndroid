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
    const val FILE_NAME = "clash-runtime.yaml"
    private const val LEGACY_DIR = "clash-runtime-yaml"
    private const val LEGACY_FILE_NAME = "runtime.yaml"

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
        val primary = putObject(store, FILE_NAME, bytes)
        if (primary.isSuccessful) return@withContext
        if (primary != 404) {
            error("HTTP $primary")
        }
        ensureCollection(store)
        val nested = putObject(store, "$LEGACY_DIR/$LEGACY_FILE_NAME", bytes)
        check(nested.isSuccessful) { "HTTP $nested" }
    }

    suspend fun download(store: UiStore): ByteArray = withContext(Dispatchers.IO) {
        runCatching { getObject(store, FILE_NAME) }.getOrElse { error ->
            if (!isNotFound(error)) throw error
            getObject(store, "$LEGACY_DIR/$LEGACY_FILE_NAME")
        }
    }

    private fun putObject(store: UiStore, relativePath: String, bytes: ByteArray): Int {
        val request = Request.Builder()
            .url(objectUrl(store, relativePath))
            .header("Authorization", authorization(store))
            .put(bytes.toRequestBody(yamlMediaType))
            .build()
        return http.newCall(request).execute().use { response ->
            response.code
        }
    }

    private fun getObject(store: UiStore, relativePath: String): ByteArray {
        val request = Request.Builder()
            .url(objectUrl(store, relativePath))
            .header("Authorization", authorization(store))
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 404) {
                error("No runtime YAML on WebDAV yet. Upload from a running client first.")
            }
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
            .url(objectUrl(store, LEGACY_DIR))
            .header("Authorization", authorization(store))
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(request).execute().use { }
    }

    private fun isNotFound(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("404") || message.contains("No runtime YAML on WebDAV")
    }

    private fun objectUrl(store: UiStore, relativePath: String): String {
        return "${requireHttpsWebDavUrl(store.webdavUrl)}/$relativePath"
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

private val Int.isSuccessful: Boolean
    get() = this in 200..299
