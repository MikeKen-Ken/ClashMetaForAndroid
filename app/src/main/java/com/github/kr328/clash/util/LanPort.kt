package com.github.kr328.clash.util

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.random.Random

object LanPort {
    const val MIN = 10000
    const val MAX = 65535
    const val WELL_KNOWN = 7890

    fun pickRandom(
        current: Int,
        random: Random = Random.Default,
        free: (Int) -> Boolean = ::canBind,
    ): Int? {
        val candidates = { generateSequence { MIN + random.nextInt(MAX - MIN + 1) } }
        return pick(current, candidates(), free) ?: pick(current, candidates()) { true }
    }

    fun pick(current: Int, candidates: Sequence<Int>, free: (Int) -> Boolean): Int? {
        return candidates.take(48).firstOrNull { port ->
            port in MIN..MAX && port != current && port != WELL_KNOWN && free(port)
        }
    }

    fun canBind(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
