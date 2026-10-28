package com.github.kr328.clash.service.runtimeyaml

import android.content.Context
import android.util.AtomicFile
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.ProfileManager
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val STATE_FILE = "runtime-yaml-auto-download.json"
private const val SLOT_NAME = "Imported runtime YAML"

private val stateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
internal data class RuntimeYamlAutoDownloadState(
    val lastCheckedAt: Long = 0,
    val etag: String = "",
    val lastModified: String = "",
    val path: String = "",
)

internal object RuntimeYamlAutoDownload {
    suspend fun runOnce(
        context: Context,
        profiles: ProfileManager,
        now: Long = System.currentTimeMillis(),
    ) {
        val settings = RuntimeYamlAutoDownloadSettings.load(context)
        RuntimeYamlAutoDownloadBackoff.remember(settings)
        if (!settings.isEnabled()) return
        val state = loadState(context)
        if (!RuntimeYamlAutoDownloadPolicy.isDue(settings.intervalHours, state.lastCheckedAt, now)) return
        if (!RuntimeYamlAutoDownloadBackoff.isOpen(now)) return

        try {
            when (val fetched = withContext(Dispatchers.IO) { RuntimeYamlRemote.fetch(settings, state) }) {
                FetchResult.NotFound -> {
                    saveState(context, state.copy(lastCheckedAt = now))
                    RuntimeYamlAutoDownloadBackoff.clear()
                    Log.i("Runtime YAML auto download: no file on WebDAV")
                }
                is FetchResult.NotModified -> {
                    saveChecked(context, now, fetched.path, fetched.etag, fetched.lastModified)
                    Log.i("Runtime YAML auto download: unchanged")
                }
                is FetchResult.Changed -> applyChanged(context, profiles, fetched, now)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w("Runtime YAML auto download failed: ${error.message}", error)
            RuntimeYamlAutoDownloadBackoff.noteFailure(now)
        }
    }

    private suspend fun applyChanged(
        context: Context,
        profiles: ProfileManager,
        fetched: FetchResult.Changed,
        now: Long,
    ) {
        val text = RuntimeYamlAutoDownloadPolicy.decodeUtf8(fetched.bytes)
        RuntimeYamlValidator.validate(text)
        if (RuntimeYamlAutoDownloadPolicy.sameContent(readLocal(context, profiles), text)) {
            saveChecked(context, now, fetched.path, fetched.etag, fetched.lastModified)
            Log.i("Runtime YAML auto download: unchanged")
            return
        }
        val cache = File(context.cacheDir, "runtime-yaml-auto-download.yaml")
        try {
            withContext(Dispatchers.IO) {
                cache.writeText(text, Charsets.UTF_8)
            }
            profiles.importRuntimeYaml(SLOT_NAME, cache.absolutePath)
        } finally {
            withContext(Dispatchers.IO) {
                cache.delete()
            }
        }
        saveChecked(context, now, fetched.path, fetched.etag, fetched.lastModified)
        Log.i("Runtime YAML auto download: imported")
    }

    private fun saveChecked(
        context: Context,
        now: Long,
        path: String,
        etag: String,
        lastModified: String,
    ) {
        saveState(
            context,
            RuntimeYamlAutoDownloadState(
                lastCheckedAt = now,
                etag = etag,
                lastModified = lastModified,
                path = path,
            ),
        )
        RuntimeYamlAutoDownloadBackoff.clear()
    }

    private suspend fun readLocal(context: Context, profiles: ProfileManager): String? {
        val uuid = RuntimeYamlProfileSlot.resolve(
            profiles.queryAll().map {
                RuntimeYamlProfileSlot.Candidate(it.uuid, it.type, it.source, it.active)
            },
        ) ?: return null
        val file = context.importedDir.resolve(uuid.toString()).resolve("config.yaml")
        return withContext(Dispatchers.IO) {
            if (!file.isFile) null else file.readText(Charsets.UTF_8)
        }
    }
}

internal object RuntimeYamlAutoDownloadBackoff {
    private val delayMs = longArrayOf(
        1L * 60L * 1000L,
        2L * 60L * 1000L,
        5L * 60L * 1000L,
        10L * 60L * 1000L,
    )
    private val failureCount = AtomicInteger(0)
    private val retryNotBeforeMs = AtomicLong(0)
    private val settingsKey = AtomicReference("")

    fun remember(settings: RuntimeYamlAutoDownloadSettings) {
        val key = listOf(
            settings.url,
            settings.username,
            settings.password,
            settings.intervalHours.toString(),
        ).joinToString("\n")
        if (settingsKey.getAndSet(key) != key) clear()
    }

    fun clear() {
        failureCount.set(0)
        retryNotBeforeMs.set(0)
    }

    fun isOpen(nowMs: Long): Boolean = nowMs >= retryNotBeforeMs.get()

    fun noteFailure(nowMs: Long) {
        val index = (failureCount.incrementAndGet() - 1).coerceAtMost(delayMs.lastIndex)
        retryNotBeforeMs.set(nowMs + delayMs[index])
    }
}

private fun loadState(context: Context): RuntimeYamlAutoDownloadState {
    val file = context.filesDir.resolve(STATE_FILE)
    if (!file.isFile) return RuntimeYamlAutoDownloadState()
    return runCatching {
        stateJson.decodeFromString(RuntimeYamlAutoDownloadState.serializer(), file.readText())
    }.getOrElse {
        Log.w("Runtime YAML auto download state reset: ${it.message}")
        RuntimeYamlAutoDownloadState()
    }
}

private fun saveState(context: Context, state: RuntimeYamlAutoDownloadState) {
    val atomic = AtomicFile(context.filesDir.resolve(STATE_FILE))
    val output = atomic.startWrite()
    try {
        output.write(
            stateJson.encodeToString(RuntimeYamlAutoDownloadState.serializer(), state)
                .encodeToByteArray(),
        )
        atomic.finishWrite(output)
    } catch (error: Throwable) {
        atomic.failWrite(output)
        throw error
    }
}
