package com.github.kr328.clash

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.*
import com.github.kr328.clash.design.R

class ExternalControlActivity : ComponentActivity(), CoroutineScope by MainScope() {
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startClashService()
            Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
        }

        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when(intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return finish()
                val url = uri.getQueryParameter("url") ?: return finish()

                launch {
                    val uuid = withProfile {
                        val type = when (uri.getQueryParameter("type")?.lowercase(Locale.getDefault())) {
                            "url" -> Profile.Type.Url
                            "file" -> Profile.Type.File
                            else -> Profile.Type.Url
                        }
                        val name = uri.getQueryParameter("name") ?: getString(R.string.new_profile)

                        create(type, name).also {
                            patch(it, name, url, 0)
                        }
                    }
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                    finish()
                }

                return
            }

            Intents.ACTION_TOGGLE_CLASH -> {
                if (isClashRunning()) stopClash() else startClash()
                return
            }

            Intents.ACTION_START_CLASH -> {
                if (!isClashRunning()) {
                    startClash()
                } else {
                    Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
                    finish()
                }

                return
            }

            Intents.ACTION_STOP_CLASH -> {
                if (isClashRunning()) {
                    stopClash()
                } else {
                    Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
                    finish()
                }

                return
            }

            else -> return finish()
        }
    }

    private fun startClash() {
        val vpnRequest = startClashService()
        if (vpnRequest != null) {
            try {
                requestVpnPermission.launch(vpnRequest)
                return
            } catch (_: Exception) {
                Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

        try {
            Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.unable_to_start_vpn, Toast.LENGTH_LONG).show()
        }

        finish()
    }

    private fun stopClash() {
        stopClashService()
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun isClashRunning(): Boolean {
        return StatusClient(this).currentProfile() != null
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }
}