package com.github.kr328.clash.connectivitysync

internal object ConnectivityStatsProtocol {
    const val PROTOCOL_VERSION = 2
    const val SLOT_COUNT = 2
    const val MAX_REMOTE_DEVICES = 32
    const val MAX_REMOTE_FILES = MAX_REMOTE_DEVICES * SLOT_COUNT
    const val MAX_RESET_ENTRIES = 4_096
    const val MAX_SAFE_COUNTER = 9_007_199_254_740_991L

    private val zeroGeneration = ResetGeneration()

    fun compare(left: ResetGeneration, right: ResetGeneration): Int {
        val counter = left.counter.compareTo(right.counter)
        return if (counter != 0) counter else left.deviceId.compareTo(right.deviceId)
    }

    fun isValidGeneration(value: ResetGeneration): Boolean =
        value.counter in 1..MAX_SAFE_COUNTER &&
            ConnectivityStatsWebDav.isValidDeviceId(value.deviceId)

    fun sanitizeResets(input: ResetWatermarks): ResetWatermarks {
        require(input.size <= MAX_RESET_ENTRIES) { "Too many connectivity reset entries" }
        return input.filter { (name, generation) ->
            name.isNotEmpty() && isValidGeneration(generation)
        }
    }

    fun mergeResets(parts: Iterable<ResetWatermarks>): ResetWatermarks {
        val merged = linkedMapOf<String, ResetGeneration>()
        parts.forEach { resets ->
            sanitizeResets(resets).forEach { (name, generation) ->
                val current = merged[name]
                if (current == null || compare(generation, current) > 0) {
                    merged[name] = generation
                }
            }
        }
        require(merged.size <= MAX_RESET_ENTRIES) { "Too many connectivity reset entries" }
        return merged
    }

    fun advanceResets(
        current: ResetWatermarks,
        names: Collection<String>,
        deviceId: String,
    ): ResetWatermarks {
        require(ConnectivityStatsWebDav.isValidDeviceId(deviceId)) { "Invalid connectivity device ID" }
        val updated = sanitizeResets(current).toMutableMap()
        names.asSequence().filter { it.isNotEmpty() }.distinct().forEach { name ->
            require(updated.size < MAX_RESET_ENTRIES || name in updated) {
                "Too many connectivity reset entries"
            }
            val previous = updated[name]?.counter ?: 0
            check(previous < MAX_SAFE_COUNTER) { "Connectivity reset counter exhausted" }
            updated[name] = ResetGeneration(previous + 1, deviceId)
        }
        return updated
    }

    fun filterSnapshotData(snapshot: DeviceSnapshot, active: ResetWatermarks): StatsData {
        return ConnectivityStatsMerge.prune(snapshot.data).filter { (name, _) ->
            val generation = snapshot.generations[name] ?: zeroGeneration
            val expected = active[name] ?: zeroGeneration
            generation == expected
        }
    }

    fun generationsFor(data: StatsData, active: ResetWatermarks): ResetWatermarks {
        return data.keys.mapNotNull { name ->
            active[name]?.let { name to it }
        }.toMap()
    }

    fun snapshotMatches(snapshot: DeviceSnapshot, ref: RemoteSnapshotRef): Boolean {
        if (snapshot.v != PROTOCOL_VERSION || snapshot.deviceId != ref.deviceId ||
            snapshot.slot != ref.slot || snapshot.slot !in 0 until SLOT_COUNT ||
            snapshot.revision !in 1..MAX_SAFE_COUNTER ||
            snapshot.revision % SLOT_COUNT != snapshot.slot.toLong()
        ) {
            return false
        }
        val resets = runCatching { sanitizeResets(snapshot.resets) }.getOrNull() ?: return false
        if (resets.size != snapshot.resets.size) return false
        if (snapshot.generations.size > MAX_RESET_ENTRIES || snapshot.generations.keys.any { it !in snapshot.data }) {
            return false
        }
        val generations = runCatching { sanitizeResets(snapshot.generations) }.getOrNull() ?: return false
        if (generations.size != snapshot.generations.size) return false
        return snapshot.data.keys.all { name ->
            val generation = generations[name] ?: zeroGeneration
            generation == (resets[name] ?: zeroGeneration)
        }
    }
}
