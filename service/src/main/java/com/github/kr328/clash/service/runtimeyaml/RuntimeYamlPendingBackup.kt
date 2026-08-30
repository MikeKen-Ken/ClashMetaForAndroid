package com.github.kr328.clash.service.runtimeyaml

import android.content.Context
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.UUID

internal class RuntimeYamlPendingBackup private constructor(
    private val context: Context,
    private val pending: Pending,
    private val backupDirectoryName: String,
) {
    companion object {
        suspend fun capture(context: Context, pending: Pending?): RuntimeYamlPendingBackup? {
            if (pending == null) return null

            val source = context.pendingDir.resolve(pending.uuid.toString())
            val backupName = ".runtime-yaml-backup-${pending.uuid}-${UUID.randomUUID()}"
            val backup = context.pendingDir.resolve(backupName)

            withContext(Dispatchers.IO) {
                if (!source.isDirectory) {
                    throw FileNotFoundException("pending profile ${pending.uuid} not found")
                }
                try {
                    check(source.copyRecursively(backup)) {
                        "failed to back up pending profile ${pending.uuid}"
                    }
                } catch (error: Exception) {
                    backup.deleteRecursively()
                    throw error
                }
            }

            return RuntimeYamlPendingBackup(context, pending, backupName)
        }
    }

    suspend fun restore() {
        val backup = context.pendingDir.resolve(backupDirectoryName)
        val target = context.pendingDir.resolve(pending.uuid.toString())

        withContext(NonCancellable + Dispatchers.IO) {
            if (!backup.isDirectory) {
                throw FileNotFoundException("pending profile backup ${pending.uuid} not found")
            }
            target.deleteRecursively()
            check(backup.copyRecursively(target)) {
                "failed to restore pending profile ${pending.uuid}"
            }
            PendingDao().insert(pending)
            backup.deleteRecursively()
        }
    }

    suspend fun discard() {
        withContext(NonCancellable + Dispatchers.IO) {
            context.pendingDir.resolve(backupDirectoryName).deleteRecursively()
        }
    }
}
