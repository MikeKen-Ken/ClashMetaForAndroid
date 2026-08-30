package com.github.kr328.clash.runtimeyaml

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeYamlDocuments {
    fun displayName(contentResolver: ContentResolver, uri: Uri): String? {
        return contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }

    fun profileName(contentResolver: ContentResolver, uri: Uri): String {
        val fileName = displayName(contentResolver, uri)
            ?.replace(Regex("\\.ya?ml$", RegexOption.IGNORE_CASE), "")
            ?.trim()
        return fileName?.takeIf(String::isNotEmpty) ?: "Imported runtime YAML"
    }

    fun defaultExportName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "runtime-$timestamp.yaml"
    }

    fun write(contentResolver: ContentResolver, uri: Uri, yaml: String) {
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(yaml)
        } ?: error("Unable to open the export destination")
    }
}
