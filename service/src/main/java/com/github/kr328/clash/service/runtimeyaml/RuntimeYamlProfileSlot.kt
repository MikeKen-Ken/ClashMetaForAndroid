package com.github.kr328.clash.service.runtimeyaml

import com.github.kr328.clash.service.model.Profile
import java.util.UUID

internal object RuntimeYamlProfileSlot {
    const val SOURCE = "content://com.github.kr328.clash/runtime-yaml-webdav-managed-slot"
    val uuid: UUID = UUID.nameUUIDFromBytes(
        "clash-runtime-yaml-profile".toByteArray(Charsets.UTF_8)
    )

    data class Candidate(
        val uuid: UUID,
        val type: Profile.Type,
        val source: String,
        val active: Boolean,
    )

    fun resolve(existing: List<Candidate>): UUID? {
        val managed = existing.filter {
            it.type == Profile.Type.File && it.source == SOURCE
        }
        return managed.firstOrNull { it.active }?.uuid
            ?: managed.firstOrNull { it.uuid == uuid }?.uuid
            ?: managed.firstOrNull()?.uuid
            ?: uuid.takeUnless { candidate -> existing.any { it.uuid == candidate } }
    }
}
