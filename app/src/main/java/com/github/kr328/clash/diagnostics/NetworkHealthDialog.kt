package com.github.kr328.clash.diagnostics

import android.content.Context
import android.content.Intent
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

suspend fun showNetworkHealth(context: Context, query: suspend () -> String) = coroutineScope {
    val labels = mapOf(
        "dns-reset" to R.string.network_health_dns,
        "route-reset" to R.string.network_health_route,
        "backup-unavailable" to R.string.network_health_backup_failed,
        "backup-verified" to R.string.network_health_backup_verified,
        "switch" to R.string.network_health_switch,
        "traffic-veto" to R.string.network_health_veto,
        "coalesced" to R.string.network_health_coalesced,
        "ignored" to R.string.network_health_ignored,
    )
    val text = TextView(context).apply {
        setPadding(32, 24, 32, 24)
        setTextIsSelectable(true)
        text = context.getString(R.string.network_health_loading)
    }
    var report: String? = null
    val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.network_health)
        .setView(ScrollView(context).apply { addView(text) })
        .setPositiveButton(R.string.network_health_refresh, null)
        .setNeutralButton(R.string.network_health_export, null)
        .setNegativeButton(R.string.network_health_close, null)
        .create()
    dialog.show()
    suspend fun refresh() {
        val refresh = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val export = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        refresh.isEnabled = false
        export.isEnabled = false
        report = null
        try {
            val raw = withContext(Dispatchers.IO) { query() }
            val data = sanitizeNetworkHealth(JSONObject(raw), labels.keys)
            report = data.toString(2)
            val none = context.getString(R.string.network_health_none)
            val lines = mutableListOf(
                context.getString(R.string.network_health_note), "",
                context.getString(R.string.network_health_observed, data.optString("observedAt", none)),
                context.getString(R.string.network_health_traffic, data.optString("lastTrafficAt", none)),
                context.getString(R.string.network_health_counts, data.optLong("switches"),
                    data.optLong("failedSearches"), data.optLong("trafficVetoes")),
            )
            data.optJSONObject("exploration")?.let {
                lines += context.getString(R.string.network_health_exploration,
                    it.optBoolean("paused").toString(), it.optLong("started"), it.optLong("completed"))
            }
            val events = data.getJSONArray("events")
            for (i in events.length() - 1 downTo 0) {
                val event = events.getJSONObject(i)
                lines += "${event.optString("at")} · ${context.getString(labels.getValue(event.getString("action")))}"
            }
            text.text = lines.joinToString("\n")
            export.isEnabled = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            text.text = context.getString(R.string.network_health_unavailable)
        } finally {
            refresh.isEnabled = true
        }
    }
    var refreshJob = launch { refresh() }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        if (!refreshJob.isActive) refreshJob = launch { refresh() }
    }
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
        report?.let { body ->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
            }, context.getString(R.string.network_health_export)))
        }
    }
    suspendCancellableCoroutine<Unit> { continuation ->
        dialog.setOnDismissListener {
            refreshJob.cancel()
            if (continuation.isActive) continuation.resume(Unit)
        }
        continuation.invokeOnCancellation { dialog.dismiss() }
    }
}

