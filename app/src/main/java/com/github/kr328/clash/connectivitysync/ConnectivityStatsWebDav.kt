package com.github.kr328.clash.connectivitysync

import android.net.Uri
import android.util.Xml
import com.github.kr328.clash.design.store.UiStore
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

internal class ConnectivityStatsWebDav(private val store: UiStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun isConfigured(): Boolean = store.webdavUrl.isNotBlank() &&
        store.webdavUsername.isNotBlank() && store.webdavPassword.isNotBlank()

    fun prepareCollections() {
        ensureCollection("clash-connectivity-sync")
        ensureCollection("clash-connectivity-sync/v1")
        ensureCollection(DEVICES_PATH)
    }

    fun upload(deviceId: String, bytes: ByteArray) {
        require(bytes.size <= MAX_SNAPSHOT_BYTES) { "Statistics snapshot is too large" }
        val request = requestBuilder("$DEVICES_PATH/$deviceId.json")
            .put(bytes.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
        }
    }

    fun listDeviceIds(): List<String> {
        val body = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype /></d:prop></d:propfind>
        """.trimIndent()
            .toRequestBody(XML_MEDIA_TYPE)
        val request = requestBuilder(DEVICES_PATH, trailingSlash = true)
            .header("Depth", "1")
            .method("PROPFIND", body)
            .build()
        val xml = http.newCall(request).execute().use { response ->
            check(response.code == 207 || response.isSuccessful) { "HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
        val ids = linkedSetOf<String>()
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("href", true)) {
                val href = parser.nextText()
                val name = Uri.parse(href).lastPathSegment
                    ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
                val id = name?.removeSuffix(".json")
                if (name?.endsWith(".json") == true && id != null && isValidDeviceId(id)) {
                    ids += id
                    check(ids.size <= MAX_REMOTE_DEVICES) {
                        "Too many connectivity sync devices"
                    }
                }
            }
            parser.next()
        }
        return ids.toList()
    }

    fun download(deviceId: String): ByteArray {
        val request = requestBuilder("$DEVICES_PATH/$deviceId.json").get().build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("Empty WebDAV response")
            check(body.contentLength() <= MAX_SNAPSHOT_BYTES) { "Statistics snapshot is too large" }
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_SNAPSHOT_BYTES) { "Statistics snapshot is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun ensureCollection(path: String) {
        val request = requestBuilder(path, trailingSlash = true)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(request).execute().use { response ->
            check(response.code in setOf(200, 201, 301, 405)) { "HTTP ${response.code}" }
        }
    }

    private fun requestBuilder(path: String, trailingSlash: Boolean = false): Request.Builder {
        val suffix = if (trailingSlash) "/" else ""
        return Request.Builder()
            .url("${baseUrl()}/$path$suffix")
            .header(
                "Authorization",
                Credentials.basic(store.webdavUsername.trim(), store.webdavPassword),
            )
    }

    private fun baseUrl(): String {
        val value = store.webdavUrl.trim().trimEnd('/')
        require(value.startsWith("https://", ignoreCase = true)) { "WebDAV URL must be https" }
        return value
    }

    companion object {
        private const val DEVICES_PATH = "clash-connectivity-sync/v1/devices"
        private const val MAX_REMOTE_DEVICES = 128
        private const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val XML_MEDIA_TYPE = "application/xml".toMediaType()

        fun isValidDeviceId(value: String): Boolean = value.length in 1..64 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
