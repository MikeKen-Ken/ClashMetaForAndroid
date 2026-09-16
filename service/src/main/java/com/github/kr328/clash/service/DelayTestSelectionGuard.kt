package com.github.kr328.clash.service

/** Serializes persisted selection cleanup against user changes during a test. */
internal class DelayTestSelectionGuard {
    private val generations = mutableMapOf<String, Long>()

    @Synchronized
    fun generation(group: String): Long = generations[group] ?: 0L

    @Synchronized
    fun <T> mutate(group: String, action: () -> T): T {
        generations[group] = generation(group) + 1
        return action()
    }

    @Synchronized
    fun ifUnchanged(group: String, expected: Long, action: () -> Unit) {
        if (generation(group) == expected) action()
    }
}
