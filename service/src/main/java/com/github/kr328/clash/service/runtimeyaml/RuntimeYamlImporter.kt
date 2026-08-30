package com.github.kr328.clash.service.runtimeyaml

import java.io.File

object RuntimeYamlImporter {
    fun writeCandidate(destination: File, source: File) {
        require(source.isFile) { "Runtime YAML is not available" }
        require(source.length() <= RuntimeYamlValidator.MAX_RUNTIME_YAML_BYTES) {
            "Runtime YAML is larger than 10 MB"
        }
        val content = source.readText(Charsets.UTF_8)
        RuntimeYamlValidator.validate(content)
        destination.parentFile?.mkdirs()
        destination.writeText(content, Charsets.UTF_8)
    }
}
