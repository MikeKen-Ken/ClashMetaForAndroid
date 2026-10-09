package com.github.kr328.clash.design.component

import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DialogProxyToolsBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * One button for mode, delay timeout, ad blocking, and concurrency.
 * The choices open in a bottom drawer so the proxy list keeps the space.
 */
class ProxyToolsDrawer(
    private val button: MaterialButton,
    private val uiStore: UiStore,
    initialMode: TunnelState.Mode?,
    initialAdsBlock: Boolean,
    private val onRequest: (ProxyDesign.Request) -> Unit,
) {
    private var mode: TunnelState.Mode? = initialMode
    private var adsBlock: Boolean = initialAdsBlock
    private var suppressMode = false
    private var modeGroup: MaterialButtonToggleGroup? = null

    init {
        val normalized = normalizeConcurrency(uiStore.proxyDelayTestConcurrency)
        if (normalized != uiStore.proxyDelayTestConcurrency) {
            uiStore.proxyDelayTestConcurrency = normalized
        }
        button.setOnClickListener { open() }
        refreshButton()
    }

    fun syncMode(mode: TunnelState.Mode) {
        this.mode = mode
        refreshButton()
        val group = modeGroup ?: return
        suppressMode = true
        try {
            checkMode(group, mode)
        } finally {
            suppressMode = false
        }
    }

    private fun open() {
        val context = button.context
        val dialog = AppBottomSheetDialog(context)
        val binding = DialogProxyToolsBinding.inflate(context.layoutInflater)
        bind(binding)
        dialog.setOnDismissListener { modeGroup = null }
        dialog.setContentView(binding.root)
        dialog.show()
        dialog.behavior.isDraggable = false
    }

    private fun bind(binding: DialogProxyToolsBinding) {
        checkMode(binding.modeToggleGroup, mode)
        when (uiStore.proxyDelayTestTimeoutMs) {
            250 -> binding.timeoutToggleGroup.check(R.id.timeout_250_btn)
            500 -> binding.timeoutToggleGroup.check(R.id.timeout_500_btn)
            1000 -> binding.timeoutToggleGroup.check(R.id.timeout_1000_btn)
            3000 -> binding.timeoutToggleGroup.check(R.id.timeout_3000_btn)
            else -> binding.timeoutToggleGroup.check(R.id.timeout_5000_btn)
        }
        binding.adsToggleGroup.check(if (adsBlock) R.id.ads_on_btn else R.id.ads_off_btn)
        when (normalizeConcurrency(uiStore.proxyDelayTestConcurrency)) {
            50 -> binding.concurrencyToggleGroup.check(R.id.concurrency_50_btn)
            100 -> binding.concurrencyToggleGroup.check(R.id.concurrency_100_btn)
            150 -> binding.concurrencyToggleGroup.check(R.id.concurrency_150_btn)
            200 -> binding.concurrencyToggleGroup.check(R.id.concurrency_200_btn)
            else -> binding.concurrencyToggleGroup.check(R.id.concurrency_30_btn)
        }

        modeGroup = binding.modeToggleGroup
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (suppressMode) return@addOnButtonCheckedListener
            val next = when {
                !isChecked -> null
                checkedId == R.id.rule_mode_btn -> TunnelState.Mode.Rule
                checkedId == R.id.global_mode_btn -> TunnelState.Mode.Global
                checkedId == R.id.direct_mode_btn -> TunnelState.Mode.Direct
                checkedId == R.id.offline_mode_btn -> TunnelState.Mode.Offline
                else -> return@addOnButtonCheckedListener
            }
            mode = next
            refreshButton()
            onRequest(ProxyDesign.Request.PatchMode(next))
        }
        binding.timeoutToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val timeoutMs = when (checkedId) {
                R.id.timeout_250_btn -> 250
                R.id.timeout_500_btn -> 500
                R.id.timeout_1000_btn -> 1000
                R.id.timeout_3000_btn -> 3000
                R.id.timeout_5000_btn -> 5000
                else -> return@addOnButtonCheckedListener
            }
            uiStore.proxyDelayTestTimeoutMs = timeoutMs
            onRequest(ProxyDesign.Request.PatchTimeout(timeoutMs))
        }
        binding.adsToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val enabled = when (checkedId) {
                R.id.ads_on_btn -> true
                R.id.ads_off_btn -> false
                else -> return@addOnButtonCheckedListener
            }
            adsBlock = enabled
            onRequest(ProxyDesign.Request.PatchAdsBlock(enabled))
        }
        binding.concurrencyToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val concurrency = when (checkedId) {
                R.id.concurrency_30_btn -> 30
                R.id.concurrency_50_btn -> 50
                R.id.concurrency_100_btn -> 100
                R.id.concurrency_150_btn -> 150
                R.id.concurrency_200_btn -> 200
                else -> return@addOnButtonCheckedListener
            }
            uiStore.proxyDelayTestConcurrency = concurrency
            onRequest(ProxyDesign.Request.PatchConcurrency(concurrency))
        }
    }

    private fun checkMode(group: MaterialButtonToggleGroup, mode: TunnelState.Mode?) {
        when (mode) {
            TunnelState.Mode.Rule -> group.check(R.id.rule_mode_btn)
            TunnelState.Mode.Global -> group.check(R.id.global_mode_btn)
            TunnelState.Mode.Direct -> group.check(R.id.direct_mode_btn)
            TunnelState.Mode.Offline -> group.check(R.id.offline_mode_btn)
            else -> group.clearChecked()
        }
    }

    private fun refreshButton() {
        button.text = button.context.getString(
            when (mode) {
                TunnelState.Mode.Global -> R.string.proxy_global
                TunnelState.Mode.Direct -> R.string.proxy_direct
                TunnelState.Mode.Offline -> R.string.proxy_offline
                TunnelState.Mode.Rule -> R.string.proxy_rule
                else -> R.string.mode
            }
        )
    }

    private fun normalizeConcurrency(value: Int): Int = when (value) {
        30, 50, 100, 150, 200 -> value
        else -> 30
    }
}
