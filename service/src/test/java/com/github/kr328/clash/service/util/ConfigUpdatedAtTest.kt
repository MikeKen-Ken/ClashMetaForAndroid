package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ConfigUpdatedAtTest {
    @Test fun missingConfigsHaveNoUpdateTime() {
        val root = File.createTempFile("profile-time", "").apply { delete() }
        assertEquals(-1L, configUpdatedAt(File(root, "pending"), File(root, "imported")))
    }

    @Test fun providerFileDoesNotChangeTheProfileUpdateTime() {
        val root = File.createTempFile("profile-time", "").apply { delete(); mkdirs() }
        try {
            val pending = File(root, "pending/config.yaml")
            val config = File(root, "imported/config.yaml")
            val provider = File(root, "imported/providers/node.yaml")
            config.parentFile!!.mkdirs()
            provider.parentFile!!.mkdirs()
            config.writeText("proxies: []\n")
            provider.writeText("payload: []\n")

            val configTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
            val providerTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(3)
            assertTrue(config.setLastModified(configTime))
            assertTrue(provider.setLastModified(providerTime))

            val updated = configUpdatedAt(pending, config)
            assertTrue(kotlin.math.abs(updated - configTime) < 2_000L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun pendingConfigWinsOverTheImportedCopy() {
        val root = File.createTempFile("profile-time", "").apply { delete(); mkdirs() }
        try {
            val pending = File(root, "pending/config.yaml")
            val imported = File(root, "imported/config.yaml")
            pending.parentFile!!.mkdirs()
            imported.parentFile!!.mkdirs()
            pending.writeText("pending\n")
            imported.writeText("imported\n")
            val pendingTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(5)
            val importedTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            assertTrue(pending.setLastModified(pendingTime))
            assertTrue(imported.setLastModified(importedTime))

            val updated = configUpdatedAt(pending, imported)
            assertTrue(kotlin.math.abs(updated - pendingTime) < 2_000L)
        } finally {
            root.deleteRecursively()
        }
    }
}
