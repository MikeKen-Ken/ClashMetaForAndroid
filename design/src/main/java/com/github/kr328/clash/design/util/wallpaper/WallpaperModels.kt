package com.github.kr328.clash.design.util.wallpaper

import kotlinx.serialization.Serializable

internal const val WALLPAPER_DEFAULT_INTERVAL_SECONDS = 300
internal const val WALLPAPER_MAX_PACK_BYTES = 20 * 1024 * 1024
internal const val WALLPAPER_MAX_ENTRY_BYTES = 8 * 1024 * 1024
internal const val WALLPAPER_MAX_FILES = 40
internal const val WALLPAPER_MANIFEST_NAME = "manifest.json"

private val safeWallpaperFileNamePattern = Regex("[A-Za-z0-9._-]+")

@Serializable
internal data class WallpaperItem(
    val id: String,
    val fileName: String,
)

@Serializable
internal data class WallpaperManifest(
    val version: Int = 1,
    val playback: String = "fixed",
    val intervalSeconds: Int = WALLPAPER_DEFAULT_INTERVAL_SECONDS,
    val activeId: String = "",
    val items: List<WallpaperItem> = emptyList(),
)

internal fun safeWallpaperFileName(name: String): String? {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    if (base.isBlank() || base.contains("..")) return null
    if (!safeWallpaperFileNamePattern.matches(base)) return null
    return base
}
