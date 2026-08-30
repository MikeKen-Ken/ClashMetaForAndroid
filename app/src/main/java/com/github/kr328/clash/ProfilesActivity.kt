package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.runtimeyaml.RuntimeYamlWebDav
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    override suspend fun main() {
        val design = ProfilesDesign(this)

        setContentDesign(design)

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> {
                            design.fetch()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create ->
                            startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.UploadRuntimeYaml ->
                            uploadRuntimeYaml(design)
                        ProfilesDesign.Request.DownloadRuntimeYaml ->
                            downloadRuntimeYaml(design)
                        ProfilesDesign.Request.UpdateAll ->
                            withProfile {
                                try {
                                    queryAll().forEach { p ->
                                        if (p.imported && p.type != Profile.Type.File)
                                            update(p.uuid)
                                    }
                                }
                                finally {
                                    withContext(Dispatchers.Main) {
                                        design.finishUpdateAll();
                                    }
                                }
                            }
                        is ProfilesDesign.Request.Update ->
                            withProfile { update(it.profile.uuid) }
                        is ProfilesDesign.Request.Delete ->
                            withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit ->
                            startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> {
                            withProfile {
                                if (it.profile.imported)
                                    setActive(it.profile)
                                else
                                    design.requestSave(it.profile)
                            }
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }

                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                    }
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            patchProfiles(queryAll())
        }
    }

    private suspend fun uploadRuntimeYaml(design: ProfilesDesign) {
        if (blockIfWebDavUnavailable(design)) return
        if (!design.confirmRuntimeYamlUpload()) return
        if (!clashRunning) {
            design.showToast(R.string.runtime_yaml_requires_running, ToastDuration.Long)
            return
        }

        try {
            val activeProfile = withProfile { queryActive() }
                ?: error(getString(R.string.running_config_no_active_profile))
            val profileDirectory = File(filesDir, "imported/${activeProfile.uuid}")
            val yaml = withClash { queryRuntimeYamlByProfile(profileDirectory.absolutePath) }
                .takeIf(String::isNotBlank)
                ?: error(getString(R.string.runtime_yaml_not_available))
            RuntimeYamlWebDav.upload(uiStore, yaml.toByteArray(Charsets.UTF_8))
            design.showToast(R.string.runtime_yaml_exported, ToastDuration.Long)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
    }

    private suspend fun downloadRuntimeYaml(design: ProfilesDesign) {
        if (blockIfWebDavUnavailable(design)) return
        if (!design.confirmRuntimeYamlDownload()) return

        try {
            val cacheFile = File(filesDir, "runtime-yaml-import.yaml")
            try {
                val bytes = RuntimeYamlWebDav.download(uiStore)
                withContext(Dispatchers.IO) {
                    cacheFile.writeBytes(bytes)
                }
                withProfile {
                    val uuid = importRuntimeYaml("Imported runtime YAML", cacheFile.absolutePath)
                    val profile = queryByUUID(uuid)
                        ?: error("Imported runtime YAML profile was not found")
                    setActive(profile)
                }
            } finally {
                cacheFile.delete()
            }
            design.fetch()
            design.showToast(R.string.runtime_yaml_imported, ToastDuration.Long)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
    }

    private suspend fun blockIfWebDavUnavailable(design: ProfilesDesign): Boolean {
        if (!RuntimeYamlWebDav.hasCredentials(uiStore)) {
            design.showToast(R.string.webdav_not_configured, ToastDuration.Long)
            return true
        }
        if (!RuntimeYamlWebDav.isConfigured(uiStore)) {
            design.showToast(R.string.runtime_yaml_webdav_https_required, ToastDuration.Long)
            return true
        }
        return false
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
        }
    }
    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason),
                ToastDuration.Long
            ){
                setAction(R.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
        }
    }
}
