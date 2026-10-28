package com.github.kr328.clash.design

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.databinding.DesignProfilesBinding
import com.github.kr328.clash.design.databinding.DialogProfilesMenuBinding
import com.github.kr328.clash.design.databinding.DialogRuntimeYamlMenuBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ProfilesDesign(context: Context) : Design<ProfilesDesign.Request>(context) {
    sealed class Request {
        object UpdateAll : Request()
        object Create : Request()
        object UploadRuntimeYaml : Request()
        object DownloadRuntimeYaml : Request()
        object RuntimeYamlAutoDownloadChanged : Request()
        data class Active(val profile: Profile) : Request()
        data class Update(val profile: Profile) : Request()
        data class Edit(val profile: Profile) : Request()
        data class Duplicate(val profile: Profile) : Request()
        data class Delete(val profile: Profile) : Request()
    }

    private val binding = DesignProfilesBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ProfileAdapter(context, this::requestActive, this::showMenu)

    private var allUpdating: Boolean
        get() = adapter.states.allUpdating;
        set(value) {
            adapter.states.allUpdating = value
        }
    private val rotateAnimation : Animation = AnimationUtils.loadAnimation(context, R.anim.rotate_infinite)

    override val root: View
        get() = binding.root

    suspend fun patchProfiles(profiles: List<Profile>) {
        adapter.apply {
            patchDataSet(this::profiles, profiles, id = { it.uuid })
        }

        val updatable = withContext(Dispatchers.Default) {
            profiles.any { it.imported && it.type != Profile.Type.File }
        }

        withContext(Dispatchers.Main) {
            binding.updateView.visibility = if (updatable) View.VISIBLE else View.GONE
        }
    }

    suspend fun requestSave(profile: Profile) {
        showToast(R.string.active_unsaved_tips, ToastDuration.Long) {
            setAction(R.string.edit) {
                requests.trySend(Request.Edit(profile))
            }
        }
    }

    fun updateElapsed() {
        adapter.updateElapsed()
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }

    private fun showMenu(profile: Profile) {
        val dialog = AppBottomSheetDialog(context)

        val binding = DialogProfilesMenuBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

        binding.master = this
        binding.self = dialog
        binding.profile = profile

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun showRuntimeYamlMenu() {
        val dialog = AppBottomSheetDialog(context)
        val menu = DialogRuntimeYamlMenuBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

        menu.master = this
        menu.self = dialog
        menu.autoDownload.subtext = runtimeYamlIntervalLabel(
            UiStore(context).runtimeYamlAutoDownloadIntervalHours,
        )
        dialog.setContentView(menu.root)
        dialog.show()
    }

    fun requestRuntimeYamlAutoDownloadInterval(dialog: Dialog) {
        dialog.dismiss()
        val store = UiStore(context)
        val values = intArrayOf(0, 1, 6, 12, 24)
        val labels = values.map { runtimeYamlIntervalLabel(it) }.toTypedArray()
        val selected = values.indexOf(store.runtimeYamlAutoDownloadIntervalHours).coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.runtime_yaml_auto_download)
            .setSingleChoiceItems(labels, selected) { picker, index ->
                store.runtimeYamlAutoDownloadIntervalHours = values[index]
                requests.trySend(Request.RuntimeYamlAutoDownloadChanged)
                picker.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runtimeYamlIntervalLabel(hours: Int): String = when (hours) {
        1 -> context.getString(R.string.runtime_yaml_auto_download_1h)
        6 -> context.getString(R.string.runtime_yaml_auto_download_6h)
        12 -> context.getString(R.string.runtime_yaml_auto_download_12h)
        24 -> context.getString(R.string.runtime_yaml_auto_download_24h)
        else -> context.getString(R.string.runtime_yaml_auto_download_off)
    }

    fun requestUploadRuntimeYaml(dialog: Dialog) {
        dialog.dismiss()
        requests.trySend(Request.UploadRuntimeYaml)
    }

    fun requestDownloadRuntimeYaml(dialog: Dialog) {
        dialog.dismiss()
        requests.trySend(Request.DownloadRuntimeYaml)
    }

    suspend fun confirmRuntimeYamlUpload(): Boolean = requestRuntimeYamlConfirmation(
        R.string.upload_runtime_yaml,
        R.string.upload_runtime_yaml_confirmation,
        R.string.upload,
    )

    suspend fun confirmRuntimeYamlDownload(): Boolean = requestRuntimeYamlConfirmation(
        R.string.download_runtime_yaml,
        R.string.download_runtime_yaml_confirmation,
        R.string.download,
    )

    private suspend fun requestRuntimeYamlConfirmation(
        title: Int,
        message: Int,
        positiveButton: Int,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButton) { _, _ -> continuation.resume(true) }
            .setNegativeButton(R.string.cancel, null)
            .show()

        dialog.setOnDismissListener {
            if (!continuation.isCompleted) continuation.resume(false)
        }
        continuation.invokeOnCancellation { dialog.dismiss() }
    }

    fun requestUpdateAll() {
        allUpdating = true;
        changeUpdateAllButtonStatus()
        requests.trySend(Request.UpdateAll)
    }

    fun finishUpdateAll() {
        allUpdating = false;
        changeUpdateAllButtonStatus()
    }

    fun requestCreate() {
        requests.trySend(Request.Create)
    }

    private fun requestActive(profile: Profile) {
        requests.trySend(Request.Active(profile))
    }

    fun requestUpdate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Update(profile))

        dialog.dismiss()
    }

    fun requestEdit(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Edit(profile))

        dialog.dismiss()
    }

    fun requestDuplicate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Duplicate(profile))

        dialog.dismiss()
    }

    fun requestDelete(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Delete(profile))

        dialog.dismiss()
    }

    private fun changeUpdateAllButtonStatus() {
        if (allUpdating) {
            binding.updateView.startAnimation(rotateAnimation)
        } else {
            binding.updateView.clearAnimation()
        }
    }
}
