package com.github.kr328.clash.service.runtimeyaml

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal object RuntimeYamlAutoDownloadPolicy {
    val intervalHours = intArrayOf(0, 1, 6, 12, 24)

    fun acceptedInterval(hours: Int): Int = if (intervalHours.contains(hours)) hours else 0

    fun isDue(intervalHours: Int, lastCheckedAt: Long, now: Long): Boolean {
        val interval = acceptedInterval(intervalHours)
        if (interval == 0) return false
        if (lastCheckedAt <= 0L) return true
        val elapsed = now - lastCheckedAt
        if (elapsed < 0L) return false
        return elapsed >= interval.toLong() * 60L * 60L * 1000L
    }

    fun sameContent(local: String?, remote: String): Boolean {
        if (local == null) return false
        return normalize(local) == normalize(remote)
    }

    fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Runtime YAML is not valid UTF-8")
        }
    }

    private fun normalize(value: String): String {
        return value.replace("\r\n", "\n").replace('\r', '\n')
    }
}
