package com.github.kr328.clash.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkHealthReportTest {
    private val at = "2026-09-14T00:00:00Z"

    @Test fun exportOmitsSecretsErrorsAndUnknownActions() {
        val raw = JSONObject().put("secret", "PRIVATE").put("lastTrafficAt", "PRIVATE")
            .put("events", JSONArray()
                .put(JSONObject().put("at", at).put("action", "dns-reset").put("reason", "PRIVATE"))
                .put(JSONObject().put("at", at).put("action", "PRIVATE")))
        val report = sanitizeNetworkHealth(raw, setOf("dns-reset"))
        assertFalse(report.toString().contains("PRIVATE"))
        assertFalse(report.has("lastTrafficAt"))
        assertEquals(1, report.getJSONArray("events").length())
    }

    @Test fun resetEventsAreBoundedAndDoNotCreateTrafficEvidence() {
        val events = JSONArray()
        repeat(1000) { events.put(JSONObject().put("at", at).put("action", "route-reset")) }
        val report = sanitizeNetworkHealth(JSONObject().put("events", events), setOf("route-reset"))
        assertEquals(64, report.getJSONArray("events").length())
        assertFalse(report.has("lastTrafficAt"))
    }

    @Test fun countersStayNonnegativeAndExplorationExportsOnlyKnownFields() {
        val raw = JSONObject().put("switches", -1).put("exploration", JSONObject()
            .put("paused", true).put("started", 4).put("completed", 3).put("node", "PRIVATE"))
        val report = sanitizeNetworkHealth(raw, emptySet())
        assertEquals(0, report.getInt("switches"))
        assertTrue(report.getJSONObject("exploration").getBoolean("paused"))
        assertEquals(4, report.getJSONObject("exploration").getInt("started"))
        assertFalse(report.toString().contains("PRIVATE"))
    }
}
