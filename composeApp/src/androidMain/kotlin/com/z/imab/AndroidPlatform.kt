package com.z.imab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.z.imab.data.BudgetStore
import com.z.imab.platform.PickedFile
import com.z.imab.platform.Pickers
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

class AndroidStorage(private val context: Context) : Storage {
    override val defaultDeviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override suspend fun readLocal(name: String): String? = withContext(Dispatchers.IO) {
        File(context.filesDir, name).takeIf { it.isFile }?.readText()
    }

    override suspend fun writeLocal(name: String, text: String) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, name)
        val tmp = File(context.filesDir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    override fun restoreSyncLocation(id: String, displayName: String): SyncLocation? {
        val uri = Uri.parse(id)
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (granted) SyncLocation(id, displayName) else null
    }

    override suspend fun readSync(location: SyncLocation): List<String> = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(Uri.parse(location.id))?.use { it.readBytes().decodeToString() }
            ?: error("Can't open the sync file. Pick it again in Settings.")
        listOf(text)
    }

    override suspend fun writeSync(location: SyncLocation, text: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(location.id)
        val bytes = text.encodeToByteArray()
        // "wt" truncates; not every cloud provider supports it, so fall back to plain "w".
        val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: error("Can't write the sync file.")
        stream.use { it.write(bytes) }
    }
}

/** Storage Access Framework pickers. OneDrive, Google Drive and Dropbox all show up in them once their apps are installed. */
class AndroidPickers(private val activity: ComponentActivity) : Pickers {
    private var pending: CompletableDeferred<Uri?>? = null

    private val openDoc = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { pending?.complete(it) }
    private val createDoc = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { pending?.complete(it) }

    override val syncHint: String =
        "On Android, tap the menu (☰) in the file picker and choose OneDrive, Drive or Dropbox. The app for that service must be installed and signed in."

    private suspend fun await(launch: () -> Unit): Uri? {
        pending?.cancel()
        val d = CompletableDeferred<Uri?>()
        pending = d
        launch()
        return d.await()
    }

    override suspend fun pickImportFile(): PickedFile? {
        val uri = await { openDoc.launch(arrayOf("*/*")) } ?: return null
        return withContext(Dispatchers.IO) {
            val text = activity.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return@withContext null
            PickedFile(displayName(uri), text)
        }
    }

    override suspend fun createSyncFile(): SyncLocation? {
        val uri = await { createDoc.launch(BudgetStore.DEFAULT_SYNC_FILE_NAME) } ?: return null
        return persist(uri)
    }

    override suspend fun openSyncFile(): SyncLocation? {
        val uri = await { openDoc.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) } ?: return null
        return persist(uri)
    }

    private fun persist(uri: Uri): SyncLocation {
        try {
            activity.contentResolver.takePersistableUriPermission(uri, RW)
        } catch (e: SecurityException) {
            error("That location doesn't allow IMAB to keep access. Try a different folder or service.")
        }
        val provider = uri.authority.orEmpty().let {
            when {
                "onedrive" in it || "skydrive" in it -> "OneDrive"
                "google" in it -> "Google Drive"
                "dropbox" in it -> "Dropbox"
                else -> null
            }
        }
        val name = displayName(uri)
        return SyncLocation(uri.toString(), if (provider != null) "$provider › $name" else name)
    }

    private fun displayName(uri: Uri): String =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
}
