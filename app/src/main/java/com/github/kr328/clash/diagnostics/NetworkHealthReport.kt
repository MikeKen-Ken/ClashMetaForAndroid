package com.github.kr328.clash.diagnostics

import org.json.JSONArray
import org.json.JSONObject

// Export an allowlist, never configuration, subscriptions, addresses or raw errors.
internal fun sanitizeNetworkHealth(raw: JSONObject, actions: Set<String>): JSONObject = JSONObject().apply {
    for (key in listOf("observedAt", "lastTrafficAt")) {
        val value = raw.optString(key)
        if (value.matches(Regex("[0-9TZ:+.\\-]{10,40}"))) put(key, value)
    }
    for (key in listOf("switches", "failedSearches", "trafficVetoes")) put(key, raw.optLong(key).coerceAtLeast(0))
    put("events", JSONArray().apply {
        val events = raw.optJSONArray("events") ?: JSONArray()
        for (i in maxOf(0, events.length() - 64) until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("action") !in actions) continue
            val at = event.optString("at")
            if (!at.matches(Regex("[0-9TZ:+.\\-]{10,40}"))) continue
            put(JSONObject().put("at", at).put("action", event.getString("action"))
                .put("durationMs", event.optLong("durationMs").coerceAtLeast(0)))
        }
    })
    raw.optJSONObject("exploration")?.let { rawProbes ->
        put("exploration", JSONObject().apply {
            put("paused", rawProbes.optBoolean("paused"))
            for (key in listOf("started", "completed", "skippedTicks")) put(key, rawProbes.optLong(key).coerceAtLeast(0))
        })
    }
}
