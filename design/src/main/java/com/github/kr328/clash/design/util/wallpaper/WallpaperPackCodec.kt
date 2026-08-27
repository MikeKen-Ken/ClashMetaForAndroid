package com.github.kr328.clash.design.util.wallpaper

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object WallpaperPackCodec {
    private const val MAX_PACK_ENTRIES = WALLPAPER_MAX_FILES + 4
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private data class PackSource(
        val item: WallpaperItem,
        val file: File,
        val size: Long,
    )

    fun encode(sourceDir: File, manifest: WallpaperManifest): ByteArray? {
        if (manifest.items.isEmpty()) return null
        require(manifest.items.size <= WALLPAPER_MAX_FILES) {
            "Wallpaper pack has too many images"
        }

        val seenIds = mutableSetOf<String>()
        val seenFileNames = mutableSetOf<String>()
        val sources = manifest.items.map { item ->
            require(item.id.isNotBlank() && seenIds.add(item.id)) {
                "Wallpaper pack contains a blank or duplicate image ID"
            }
            val fileName = safeWallpaperFileName(item.fileName)
                ?: throw IllegalArgumentException("Wallpaper pack contains an invalid file name")
            require(item.fileName == fileName) {
                "Wallpaper pack contains a path-like image name"
            }
            require(seenFileNames.add(fileName)) {
                "Wallpaper pack contains duplicate file names"
            }
            val source = File(sourceDir, fileName)
            val sourceSize = if (source.isFile) source.length() else 0L
            require(sourceSize > 0L) {
                "Wallpaper image is missing or empty: $fileName"
            }
            require(sourceSize <= WALLPAPER_MAX_ENTRY_BYTES) {
                "Wallpaper image exceeds the per-file limit: $fileName"
            }
            PackSource(item.copy(fileName = fileName), source, sourceSize)
        }

        val resolvedItems = sources.map { it.item }
        val activeId = manifest.activeId.takeIf { active ->
            resolvedItems.any { it.id == active }
        } ?: resolvedItems.first().id
        val resolvedManifest = manifest.copy(items = resolvedItems, activeId = activeId)
        val manifestBytes = json.encodeToString(
            WallpaperManifest.serializer(),
            resolvedManifest,
        ).toByteArray()
        require(manifestBytes.size <= WALLPAPER_MAX_ENTRY_BYTES) {
            "Wallpaper manifest exceeds the per-entry limit"
        }

        val declaredPayloadBytes = sources.fold(manifestBytes.size.toLong()) { total, source ->
            total + source.size
        }
        require(declaredPayloadBytes <= WALLPAPER_MAX_PACK_BYTES) {
            "Wallpaper pack exceeds the total size limit"
        }

        val out = ByteArrayOutputStream()
        var actualPayloadBytes = manifestBytes.size
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(WALLPAPER_MANIFEST_NAME))
            zip.write(manifestBytes)
            zip.closeEntry()

            for (source in sources) {
                zip.putNextEntry(ZipEntry("images/${source.item.fileName}"))
                val remaining = WALLPAPER_MAX_PACK_BYTES - actualPayloadBytes
                val written = source.file.inputStream().use { input ->
                    copyBounded(input::read, zip, minOf(WALLPAPER_MAX_ENTRY_BYTES, remaining))
                }
                require(written.toLong() == source.size) {
                    "Wallpaper image changed while creating the pack"
                }
                actualPayloadBytes += written
                zip.closeEntry()
            }
        }

        return out.toByteArray().also { encoded ->
            require(encoded.size <= WALLPAPER_MAX_PACK_BYTES) {
                "Compressed wallpaper pack exceeds the upload limit"
            }
        }
    }

    fun apply(
        filesDir: File,
        liveDirectoryName: String,
        bytes: ByteArray,
        validateImage: (File) -> Boolean,
    ): Boolean {
        require(bytes.size <= WALLPAPER_MAX_PACK_BYTES) {
            "Compressed wallpaper pack exceeds the download limit"
        }

        val token = UUID.randomUUID().toString().replace("-", "")
        val staging = File(filesDir, ".$liveDirectoryName-import-$token")
        val backup = File(filesDir, ".$liveDirectoryName-backup-$token")
        val live = File(filesDir, liveDirectoryName)
        var installed = false

        try {
            require(staging.mkdirs()) { "Unable to create wallpaper staging directory" }
            val manifest = extractAndValidate(staging, bytes, validateImage)
            File(staging, WALLPAPER_MANIFEST_NAME).writeText(
                json.encodeToString(WallpaperManifest.serializer(), manifest),
            )
            replaceDirectory(live, staging, backup)
            installed = true
            return true
        } finally {
            staging.deleteRecursively()
            if (installed) backup.deleteRecursively()
        }
    }

    private fun extractAndValidate(
        staging: File,
        bytes: ByteArray,
        validateImage: (File) -> Boolean,
    ): WallpaperManifest {
        var manifestPayload: ByteArray? = null
        var totalBytes = 0
        var entryCount = 0
        var imageCount = 0
        val extractedFileNames = mutableSetOf<String>()

        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MAX_PACK_ENTRIES) {
                    "Wallpaper pack has too many entries"
                }
                val name = normalizedEntryName(entry.name)
                val remaining = WALLPAPER_MAX_PACK_BYTES - totalBytes
                require(remaining >= 0) { "Wallpaper pack exceeds the total size limit" }

                when {
                    entry.isDirectory -> {
                        totalBytes += copyBounded(
                            zip::read,
                            null,
                            minOf(WALLPAPER_MAX_ENTRY_BYTES, remaining),
                        )
                    }
                    name == WALLPAPER_MANIFEST_NAME -> {
                        require(manifestPayload == null) {
                            "Wallpaper pack contains multiple manifests"
                        }
                        val out = ByteArrayOutputStream()
                        totalBytes += copyBounded(
                            zip::read,
                            out,
                            minOf(WALLPAPER_MAX_ENTRY_BYTES, remaining),
                        )
                        manifestPayload = out.toByteArray()
                    }
                    name.startsWith("images/") -> {
                        val fileName = safeWallpaperFileName(name.substringAfterLast('/'))
                            ?: throw IllegalArgumentException(
                                "Wallpaper pack contains an invalid file name",
                            )
                        require(name == "images/$fileName") {
                            "Wallpaper pack contains a nested image path"
                        }
                        require(extractedFileNames.add(fileName)) {
                            "Wallpaper pack contains duplicate image names"
                        }
                        imageCount += 1
                        require(imageCount <= WALLPAPER_MAX_FILES) {
                            "Wallpaper pack has too many images"
                        }
                        val destination = File(staging, fileName)
                        destination.outputStream().use { output ->
                            totalBytes += copyBounded(
                                zip::read,
                                output,
                                minOf(WALLPAPER_MAX_ENTRY_BYTES, remaining),
                            )
                        }
                    }
                    else -> {
                        totalBytes += copyBounded(
                            zip::read,
                            null,
                            minOf(WALLPAPER_MAX_ENTRY_BYTES, remaining),
                        )
                    }
                }
                zip.closeEntry()
            }
        }

        val payload = manifestPayload
            ?: throw IllegalArgumentException("Wallpaper pack is missing its manifest")
        val loaded = json.decodeFromString(WallpaperManifest.serializer(), payload.decodeToString())
        require(loaded.version == 1) { "Unsupported wallpaper pack version" }
        require(loaded.items.isNotEmpty()) { "Wallpaper pack is empty" }
        require(loaded.items.size <= WALLPAPER_MAX_FILES) {
            "Wallpaper manifest has too many images"
        }

        val referencedIds = mutableSetOf<String>()
        val referencedNames = mutableSetOf<String>()
        val resolvedItems = loaded.items.map { item ->
            require(item.id.isNotBlank() && referencedIds.add(item.id)) {
                "Wallpaper manifest contains a blank or duplicate image ID"
            }
            val fileName = safeWallpaperFileName(item.fileName)
                ?: throw IllegalArgumentException("Wallpaper manifest contains an invalid file name")
            require(item.fileName == fileName) {
                "Wallpaper manifest contains a path-like image name"
            }
            require(referencedNames.add(fileName)) {
                "Wallpaper manifest contains duplicate image names"
            }
            val image = File(staging, fileName)
            require(image.isFile && validateImage(image)) {
                "Wallpaper pack contains a missing or invalid image: $fileName"
            }
            item.copy(fileName = fileName)
        }
        val activeId = loaded.activeId.takeIf { active ->
            resolvedItems.any { it.id == active }
        } ?: resolvedItems.first().id
        return loaded.copy(items = resolvedItems, activeId = activeId)
    }

    private fun normalizedEntryName(rawName: String): String {
        val normalized = rawName.replace('\\', '/')
        require(!normalized.startsWith('/')) { "Wallpaper pack contains an absolute path" }
        require(normalized.split('/').none { it == ".." }) {
            "Wallpaper pack contains path traversal"
        }
        return normalized.trimStart('/')
    }

    private fun copyBounded(
        read: (ByteArray) -> Int,
        output: OutputStream?,
        maxBytes: Int,
    ): Int {
        val buffer = ByteArray(8192)
        var written = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(written + count <= maxBytes) {
                "Wallpaper pack entry exceeds its allowed size"
            }
            output?.write(buffer, 0, count)
            written += count
        }
        return written
    }

    private fun replaceDirectory(live: File, staging: File, backup: File) {
        val hadLiveDirectory = live.exists()
        if (hadLiveDirectory) {
            require(live.renameTo(backup)) {
                "Unable to preserve the existing wallpaper library"
            }
        }

        if (staging.renameTo(live)) return

        if (hadLiveDirectory && !backup.renameTo(live)) {
            throw IllegalStateException(
                "Unable to install the wallpaper library or restore its backup at ${backup.path}",
            )
        }
        throw IllegalStateException("Unable to install the wallpaper library")
    }
}
