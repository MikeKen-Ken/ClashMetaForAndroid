package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.runtimeyaml.RuntimeYamlDocuments
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
        if (!design.confirmRuntimeYamlUpload()) return

        val source = startActivityForResult(
            ActivityResultContracts.OpenDocument(),
            arrayOf("application/yaml", "text/yaml", "text/plain", "application/octet-stream"),
        ) ?: return

        try {
            val profileName = RuntimeYamlDocuments.profileName(contentResolver, source)
            withProfile {
                val uuid = importRuntimeYaml(profileName, source.toString())
                val profile = queryByUUID(uuid)
                    ?: error("Imported runtime YAML profile was not found")
                setActive(profile)
            }
            design.fetch()
            design.showToast(R.string.runtime_yaml_imported, ToastDuration.Long)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
    }

    private suspend fun downloadRuntimeYaml(design: ProfilesDesign) {
        if (!design.confirmRuntimeYamlDownload()) return
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
            val destination = startActivityForResult(
                ActivityResultContracts.CreateDocument("application/yaml"),
                RuntimeYamlDocuments.defaultExportName(),
            ) ?: return

            withContext(Dispatchers.IO) {
                RuntimeYamlDocuments.write(contentResolver, destination, yaml)
            }
            design.showToast(R.string.runtime_yaml_exported, ToastDuration.Long)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
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
