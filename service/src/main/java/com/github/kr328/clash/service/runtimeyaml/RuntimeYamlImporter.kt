package com.github.kr328.clash.service.runtimeyaml

import java.io.File

object RuntimeYamlImporter {
    fun writeCandidate(destination: File, content: String) {
        try {
            RuntimeYamlValidator.validate(content)
            destination.writeText(content, Charsets.UTF_8)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
}
