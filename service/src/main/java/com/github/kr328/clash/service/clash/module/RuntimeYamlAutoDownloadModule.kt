package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.ProfileManager
import com.github.kr328.clash.service.runtimeyaml.RuntimeYamlAutoDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Downloads the shared runtime YAML on its interval for as long as this service is running. */
class RuntimeYamlAutoDownloadModule(service: Service) : Module<Unit>(service) {
    override suspend fun run() {
        val profiles = ProfileManager(service)
        try {
            while (true) {
                runOnce(profiles)
                delay(TimeUnit.MINUTES.toMillis(1))
            }
        } finally {
            profiles.cancel()
        }
    }

    private suspend fun runOnce(profiles: ProfileManager) {
        try {
            RuntimeYamlAutoDownload.runOnce(service, profiles)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w("Automatic runtime YAML download failed: ${error.message}", error)
        }
    }
}
