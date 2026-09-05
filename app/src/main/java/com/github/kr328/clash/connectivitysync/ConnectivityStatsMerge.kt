package com.github.kr328.clash.connectivitysync

import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal object ConnectivityStatsMerge {
    private const val RETENTION_DAYS = 30
    private const val MAX_SAFE_COUNT = 9_007_199_254_740_991L
    fun prune(data: StatsData, now: Date = Date()): StatsData {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
        }
        val cutoffCalendar = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, -(RETENTION_DAYS - 1))
        }
        val cutoff = dayFormat.format(cutoffCalendar.time)
        val today = dayFormat.format(now)
        return data.mapNotNull { (name, entry) ->
            val days = entry.days.mapNotNull { (day, counts) ->
                val sanitized = DayCounts(
                    s = counts.s.coerceIn(0, MAX_SAFE_COUNT),
                    f = counts.f.coerceIn(0, MAX_SAFE_COUNT),
                    ds = counts.ds.coerceIn(0, MAX_SAFE_COUNT),
                )
                if (parseDay(day, dayFormat) && day >= cutoff && day <= today &&
                    (sanitized.s > 0 || sanitized.f > 0 || sanitized.ds > 0)
                ) {
                    day to sanitized
                } else {
                    null
                }
            }.toMap()
            if (days.isEmpty()) null else name to ProxyEntry(days, ls = entry.ls.coerceAtLeast(0))
        }.toMap()
    }

    fun subtract(current: StatsData, imported: StatsData): StatsData {
        return current.mapNotNull { (name, entry) ->
            val previousDays = imported[name]?.days.orEmpty()
            val days = entry.days.mapNotNull { (day, counts) ->
                val previous = previousDays[day] ?: DayCounts()
                val own = DayCounts(
                    s = (counts.s - previous.s).coerceAtLeast(0),
                    f = (counts.f - previous.f).coerceAtLeast(0),
                    ds = (counts.ds - previous.ds).coerceAtLeast(0),
                )
                if (own.s == 0L && own.f == 0L && own.ds == 0L) null else day to own
            }.toMap()
            if (days.isEmpty()) null else name to ProxyEntry(days, ls = entry.ls.coerceAtLeast(0))
        }.toMap()
    }

    fun sum(parts: Iterable<StatsData>): StatsData {
        val merged = mutableMapOf<String, MutableMap<String, DayCounts>>()
        val lastSuccess = mutableMapOf<String, Long>()
        parts.forEach { data ->
            data.forEach { (name, entry) ->
                val days = merged.getOrPut(name) { mutableMapOf() }
                entry.days.forEach { (day, counts) ->
                    val current = days[day] ?: DayCounts()
                    days[day] = DayCounts(
                        s = safeAdd(current.s, counts.s),
                        f = safeAdd(current.f, counts.f),
                        ds = safeAdd(current.ds, counts.ds),
                    )
                }
                lastSuccess[name] = maxOf(lastSuccess[name] ?: 0L, entry.ls.coerceAtLeast(0))
            }
        }
        return prune(
            merged.mapValues { (name, days) ->
                ProxyEntry(days, ls = lastSuccess[name] ?: 0L)
            },
        )
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (left < 0 || right < 0) return 0
        return if (left > MAX_SAFE_COUNT - right) MAX_SAFE_COUNT else left + right
    }

    private fun parseDay(value: String, dayFormat: SimpleDateFormat): Boolean {
        val position = ParsePosition(0)
        val parsed = dayFormat.parse(value, position)
        return parsed != null && position.index == value.length
    }
}
