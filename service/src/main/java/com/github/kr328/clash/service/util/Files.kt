package com.github.kr328.clash.service.util

import android.content.Context
import java.io.File

val Context.importedDir: File
    get() = filesDir.resolve("imported")

val Context.pendingDir: File
    get() = filesDir.resolve("pending")

val Context.processingDir: File
    get() = filesDir.resolve("processing")

/** Subscription download time. Provider files beside config.yaml are refreshed by the core. */
fun configUpdatedAt(pendingConfig: File, importedConfig: File): Long {
    pendingConfig.modifiedAt()?.let { return it }
    importedConfig.modifiedAt()?.let { return it }
    return -1L
}

private fun File.modifiedAt(): Long? {
    if (!isFile) return null
    val modified = lastModified()
    return modified.takeIf { it > 0L }
}