package com.github.kr328.clash

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.RunningConfigDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.share.RuntimeLanShareServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File

class RunningConfigActivity : BaseActivity<RunningConfigDesign>() {
    private var currentRuntimeYaml: String? = null

    override suspend fun main() {
        val design = RunningConfigDesign(this)
        setContentDesign(design)
        design.setConfigText(loadConfigText())

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated, Event.ProfileChanged, Event.ProfileLoaded -> {
                            design.setConfigText(loadConfigText())
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        RunningConfigDesign.Request.ShareByQr -> {
                            showRuntimeShareQr(design)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runBlocking {
            RuntimeLanShareServer.stop()
        }
        super.onDestroy()
    }

    private suspend fun loadConfigText(): String {
        val activeProfile = withProfile { queryActive() }
            ?: return getString(R.string.running_config_no_active_profile)
        val configFile = File(filesDir, "imported/${activeProfile.uuid}/config.yaml")
        if (!configFile.exists() || !configFile.isFile) {
            return getString(R.string.running_config_file_not_found)
        }

        val profileYaml = withContext(Dispatchers.IO) { configFile.readText() }
        if (!clashRunning) {
            currentRuntimeYaml = null
            return if (profileYaml.isBlank()) getString(R.string.running_config_empty) else profileYaml
        }

        val runtimeYaml = withClash { queryRuntimeYamlByProfile(configFile.parentFile.absolutePath) }
        currentRuntimeYaml = runtimeYaml
        val display = if (runtimeYaml.isBlank()) profileYaml else runtimeYaml
        return if (display.isBlank()) getString(R.string.running_config_empty) else display
    }

    private suspend fun showRuntimeShareQr(design: RunningConfigDesign) {
        if (!clashRunning) {
            design.showToast(R.string.running_config_share_only_when_running, ToastDuration.Long)
            return
        }

        val runtimeYaml = currentRuntimeYaml ?: loadConfigText().also {
            design.setConfigText(it)
        }
        val shareInfo = try {
            RuntimeLanShareServer.start(runtimeYaml)
        } catch (e: Exception) {
            design.showToast(R.string.running_config_share_start_failed, ToastDuration.Long)
            return
        }
        showQrDialog(shareInfo.primaryUrl, shareInfo.ttlSecs)
    }

    private fun showQrDialog(url: String, ttlSecs: Long) {
        val size = (resources.displayMetrics.density * 240).toInt()
        val qr = createQrBitmap(url, size, size)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val image = ImageView(this).apply { setImageBitmap(qr) }
        val text = TextView(this).apply {
            text = getString(R.string.running_config_share_url_hint, ttlSecs, url)
            setTextIsSelectable(true)
        }
        container.addView(image)
        container.addView(text)

        AlertDialog.Builder(this)
            .setTitle(R.string.share_qr_code)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun createQrBitmap(content: String, width: Int, height: Int): Bitmap {
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
