package com.github.kr328.clash.service.runtimeyaml

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

internal object RuntimeYamlValidator {
    const val MAX_RUNTIME_YAML_BYTES = 10L * 1024L * 1024L

    fun validate(content: String) {
        require(content.isNotEmpty()) { "Runtime YAML is empty" }
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_RUNTIME_YAML_BYTES) {
            "Runtime YAML is larger than 10 MB"
        }
        val loaded = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(content)
        } catch (_: YAMLException) {
            error("Runtime YAML must contain a top-level mapping")
        }
        require(loaded is Map<*, *> && loaded.isNotEmpty()) {
            "Runtime YAML must contain a top-level mapping"
        }
    }
}
