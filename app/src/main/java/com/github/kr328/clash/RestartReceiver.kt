package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.util.startClashService

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (StatusProvider.shouldStartClashOnBoot) {
                    val vpnRequest = context.startClashService()
                    if (vpnRequest != null) {
                        Log.i("VPN permission is required for auto-start; skipping this startup")
                    }
                }
            }
        }
    }
}