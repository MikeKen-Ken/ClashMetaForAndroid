package com.github.kr328.clash.service.runtimeyaml

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal object RuntimeYamlRemote {
    private const val PRIMARY = "clash-runtime.yaml"
    private const val LEGACY = "clash-runtime-yaml/runtime.yaml"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun fetch(
        settings: RuntimeYamlAutoDownloadSettings,
        state: RuntimeYamlAutoDownloadState,
    ): FetchResult {
        val primary = get(settings, PRIMARY, state)
        if (primary !is FetchResult.NotFound) return primary
        return get(settings, LEGACY, state)
    }

    private fun get(
        settings: RuntimeYamlAutoDownloadSettings,
        path: String,
        state: RuntimeYamlAutoDownloadState,
    ): FetchResult {
        val request = Request.Builder()
            .url(objectUrl(settings.url, path))
            .header("Authorization", Credentials.basic(settings.username.trim(), settings.password))
            .get()
        if (state.path == path) {
            if (state.etag.isNotBlank()) request.header("If-None-Match", state.etag)
            if (state.lastModified.isNotBlank()) request.header("If-Modified-Since", state.lastModified)
        }
        return http.newCall(request.build()).execute().use { response ->
            when (response.code) {
                304 -> FetchResult.NotModified(
                    path = path,
                    etag = response.header("ETag").orEmpty().ifBlank { state.etag },
                    lastModified = response.header("Last-Modified").orEmpty().ifBlank { state.lastModified },
                )
                404 -> FetchResult.NotFound
                in 200..299 -> {
                    val bytes = readLimited(response.body?.byteStream(), response.body?.contentLength() ?: -1)
                    FetchResult.Changed(
                        bytes = bytes,
                        path = path,
                        etag = response.header("ETag").orEmpty(),
                        lastModified = response.header("Last-Modified").orEmpty(),
                    )
                }
                else -> error("HTTP ${response.code}")
            }
        }
    }

    private fun readLimited(input: java.io.InputStream?, declaredLength: Long): ByteArray {
        val body = input ?: error("Empty WebDAV response")
        val limit = RuntimeYamlValidator.MAX_RUNTIME_YAML_BYTES
        if (declaredLength > limit) error("Runtime YAML is larger than 10 MB")
        body.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) error("Runtime YAML is larger than 10 MB")
                output.write(buffer, 0, count)
            }
            if (total == 0L) error("Runtime YAML is empty")
            return output.toByteArray()
        }
    }

    private fun objectUrl(raw: String, relativePath: String): String {
        val base = raw.trim().trimEnd('/')
        require(base.startsWith("https://", ignoreCase = true)) { "WebDAV URL must be https" }
        return "$base/$relativePath"
    }
}

internal sealed class FetchResult {
    data object NotFound : FetchResult()

    data class NotModified(
        val path: String,
        val etag: String,
        val lastModified: String,
    ) : FetchResult()

    data class Changed(
        val bytes: ByteArray,
        val path: String,
        val etag: String,
        val lastModified: String,
    ) : FetchResult()
}
