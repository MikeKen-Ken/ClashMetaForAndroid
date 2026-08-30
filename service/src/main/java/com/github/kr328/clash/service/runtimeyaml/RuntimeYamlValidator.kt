package com.github.kr328.clash.service.runtimeyaml

import org.json.JSONObject

internal object RuntimeYamlValidator {
    const val MAX_RUNTIME_YAML_BYTES = 10L * 1024L * 1024L

    fun validate(content: String) {
        require(content.isNotEmpty()) { "Runtime YAML is empty" }
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_RUNTIME_YAML_BYTES) {
            "Runtime YAML is larger than 10 MB"
        }
        require(isNonEmptyMapping(content)) {
            "Runtime YAML must contain a top-level mapping"
        }
    }

    internal fun isNonEmptyMapping(content: String): Boolean {
        val trimmed = content.trim().trimStart('\uFEFF')
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("{")) {
            runCatching { JSONObject(trimmed) }.getOrNull()?.let { json ->
                return json.length() > 0
            }
        }
        val significant = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it != "---" && it != "..." }
            .toList()
        if (significant.isEmpty()) return false
        val first = significant.first()
        if (first.startsWith("-")) return false
        return first.contains(':')
    }
}
