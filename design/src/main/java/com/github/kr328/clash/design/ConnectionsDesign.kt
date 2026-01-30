package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.core.model.ConnectionsSnapshot
import com.github.kr328.clash.design.adapter.ConnectionAdapter
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConnectionsDesign(context: Context) : Design<ConnectionsDesign.Request>(context) {
    sealed class Request {
        data class CloseConnection(val connection: Connection) : Request()
    }

    private val binding = DesignConnectionsBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ConnectionAdapter(context) { conn ->
        requests.trySend(Request.CloseConnection(conn))
    }

    override val root: View
        get() = binding.root

    suspend fun patchConnections(snapshot: ConnectionsSnapshot) {
        withContext(Dispatchers.Main) {
            adapter.connections = snapshot.connections
            adapter.notifyDataSetChanged()
        }
    }

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.recyclerList.applyLinearAdapter(context, adapter)
    }
}
