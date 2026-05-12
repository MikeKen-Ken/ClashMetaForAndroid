package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignRunningConfigBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class RunningConfigDesign(context: Context) : Design<RunningConfigDesign.Request>(context) {
    enum class Request {
        ShareByQr,
    }

    private val binding = DesignRunningConfigBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    fun setConfigText(content: String) {
        binding.configView.text = content
    }

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        binding.activityBarShareView.setOnClickListener {
            requests.trySend(Request.ShareByQr)
        }
    }
}
