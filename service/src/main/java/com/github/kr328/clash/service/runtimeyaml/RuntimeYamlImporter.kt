package com.github.kr328.clash.service.runtimeyaml

import android.content.Context
import android.net.Uri
import java.io.File

object RuntimeYamlImporter {
    fun copyCandidate(context: Context, source: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(source)
            ?: error("Unable to open the selected runtime YAML")

        try {
            input.use { reader ->
                destination.outputStream().buffered().use { writer ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L

                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break

                        total += read
                        require(total <= RuntimeYamlValidator.MAX_RUNTIME_YAML_BYTES) {
                            "Runtime YAML is larger than 10 MB"
                        }
                        writer.write(buffer, 0, read)
                    }

                    require(total > 0L) { "Runtime YAML is empty" }
                }
            }
            RuntimeYamlValidator.validate(destination.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
}
