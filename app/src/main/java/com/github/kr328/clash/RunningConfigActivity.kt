package com.github.kr328.clash

import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.RunningConfigDesign
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

class RunningConfigActivity : BaseActivity<RunningConfigDesign>() {
    override suspend fun main() {
        val design = RunningConfigDesign(this)
        setContentDesign(design)
        design.setConfigText(loadRunningConfigText())

        while (isActive) {
            events.receive()
        }
    }

    private suspend fun loadRunningConfigText(): String {
        if (!clashRunning) {
            return getString(R.string.running_config_not_running)
        }

        val activeProfile = withProfile { queryActive() }
            ?: return getString(R.string.running_config_no_active_profile)

        val configFile = File(filesDir, "imported/${activeProfile.uuid}/config.yaml")
        if (!configFile.exists() || !configFile.isFile) {
            return getString(R.string.running_config_file_not_found)
        }

        return withContext(Dispatchers.IO) {
            val content = configFile.readText()
            if (content.isBlank()) {
                getString(R.string.running_config_empty)
            } else {
                content
            }
        }
    }
}
