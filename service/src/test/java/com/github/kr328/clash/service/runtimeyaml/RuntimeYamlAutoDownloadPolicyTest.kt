package com.github.kr328.clash.service.runtimeyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeYamlAutoDownloadPolicyTest {
    private val hour = 60L * 60L * 1000L

    @Test fun unknownOrZeroIntervalIsNeverDue() {
        assertFalse(RuntimeYamlAutoDownloadPolicy.isDue(0, 0, 1))
        assertFalse(RuntimeYamlAutoDownloadPolicy.isDue(48, 0, 1))
        assertEquals(0, RuntimeYamlAutoDownloadPolicy.acceptedInterval(3))
        assertEquals(6, RuntimeYamlAutoDownloadPolicy.acceptedInterval(6))
    }

    @Test fun firstCheckAndElapsedIntervalAreDue() {
        assertTrue(RuntimeYamlAutoDownloadPolicy.isDue(1, 0, 5_000))
        assertFalse(RuntimeYamlAutoDownloadPolicy.isDue(1, 1_000, 1_000 + hour - 1))
        assertTrue(RuntimeYamlAutoDownloadPolicy.isDue(1, 1_000, 1_000 + hour))
        assertFalse(RuntimeYamlAutoDownloadPolicy.isDue(6, 10_000, 9_000))
    }

    @Test fun identicalTextSkipsAndAMissingSlotApplies() {
        assertTrue(RuntimeYamlAutoDownloadPolicy.sameContent("mode: rule\r\n", "mode: rule\n"))
        assertFalse(RuntimeYamlAutoDownloadPolicy.sameContent("mode: rule\n", "mode: global\n"))
        assertFalse(RuntimeYamlAutoDownloadPolicy.sameContent(null, "mode: rule\n"))
    }

    @Test fun malformedUtf8IsRejected() {
        assertEquals("ok", RuntimeYamlAutoDownloadPolicy.decodeUtf8("ok".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeYamlAutoDownloadPolicy.decodeUtf8(byteArrayOf(0xFF.toByte()))
        }
    }
}
