package com.z.imab

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import com.z.imab.receipt.OcrLine
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.provider.DocumentsContract
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

    private val keyVault = AndroidKeyVault(File(context.noBackupFilesDir, "budget-key.bin"))

    override suspend fun loadKey(): String? = withContext(Dispatchers.IO) { keyVault.load() }

    override suspend fun saveKey(value: String): Boolean = withContext(Dispatchers.IO) { keyVault.save(value) }

    override suspend fun deleteKey() = withContext(Dispatchers.IO) { keyVault.delete() }

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

    override suspend fun deleteLocal(name: String) = withContext(Dispatchers.IO) {
        File(context.filesDir, name).delete()
        Unit
    }

    override suspend fun listLocal(prefix: String): List<String> = withContext(Dispatchers.IO) {
        context.filesDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.sortedByDescending { it.lastModified() }?.map { it.name }.orEmpty()
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

    override suspend fun syncStamp(location: SyncLocation): String? = withContext(Dispatchers.IO) {
        val cols = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
        runCatching {
            context.contentResolver.query(Uri.parse(location.id), cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) "${c.getLong(0)}:${c.getLong(1)}" else null
            }
        }.getOrNull()
    }

    override suspend fun writeSync(location: SyncLocation, text: String): Unit = withContext(Dispatchers.IO) {
        val uri = Uri.parse(location.id)
        val bytes = text.encodeToByteArray()
        // Cloud providers differ in which modes they honour, and some (OneDrive) hand back a file
        // that opens in a mode but can't then be written ("EBADF"). So each way of writing is
        // tried in turn until one works, and the error says what each one did if none does.
        val failures = mutableListOf<String>()
        for (mode in listOf("rwt", "wt", "w")) {
            val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, mode) }
                .getOrElse { failures += "$mode: ${it.message}"; null } ?: continue
            val written = runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                    // Not every provider truncates, so cut the file to length ourselves too;
                    // otherwise a shorter write leaves the end of the old file behind.
                    runCatching { out.channel.truncate(0) }
                    out.write(bytes)
                    out.flush()
                    runCatching { out.channel.truncate(bytes.size.toLong()) }
                }
            }
            if (written.isSuccess) return@withContext
            failures += "$mode: ${written.exceptionOrNull()?.message}"
        }
        // Last, a plain stream, which providers that stream uploads through a pipe always accept.
        for (mode in listOf("wt", "w")) {
            val written = runCatching {
                (context.contentResolver.openOutputStream(uri, mode) ?: error("no stream")).use { it.write(bytes); it.flush() }
            }
            if (written.isSuccess) return@withContext
            failures += "stream $mode: ${written.exceptionOrNull()?.message}"
        }
        // Whether the cloud app says the file can be changed, and whether IMAB still has write access.
        val supportsWrite = runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                if (c.moveToFirst()) (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0 else null
            }
        }.getOrNull()
        val granted = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.isWritePermission
        Log.w("IMAB", "Couldn't write the sync file: ${failures.joinToString("; ")}; supportsWrite=$supportsWrite granted=$granted")
        error(
            when {
                granted == false -> "IMAB no longer has permission to change the sync file. Choose it again with \"Use existing sync file\"."
                supportsWrite == false -> "The cloud app says this sync file can't be changed (it may be read-only, shared with you, or locked). " +
                    "Check it in the cloud app, or choose another file."
                else -> "The cloud app wouldn't save the sync file (${failures.last()}). Open the cloud app to check it's signed in, " +
                    "then choose the file again with \"Use existing sync file\"."
            }
        )
    }
}

/** Storage Access Framework pickers. OneDrive, Google Drive and Dropbox all show up in them once their apps are installed. */
class AndroidPickers(private val activity: ComponentActivity) : Pickers {
    private var pending: CompletableDeferred<Uri?>? = null

    private val openDoc = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { pending?.complete(it) }
    private val createDoc = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { pending?.complete(it) }
    private var pendingPhoto: CompletableDeferred<Boolean>? = null
    private val takePicture = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { pendingPhoto?.complete(it) }
    private val pickMedia = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { pending?.complete(it) }

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

    override suspend fun saveFile(suggestedName: String, text: String): String? {
        val uri = await { createDoc.launch(suggestedName) } ?: return null
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri, "w")?.use { it.write(text.encodeToByteArray()) }
                ?: error("Can't write the archive.")
        }
        return displayName(uri)
    }

    override suspend fun pickArchive(): PickedFile? {
        val uri = await { openDoc.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) } ?: return null
        return withContext(Dispatchers.IO) {
            val text = activity.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return@withContext null
            PickedFile(displayName(uri), text)
        }
    }

    override val canTakePhoto: Boolean
        get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private val authority get() = "${activity.packageName}.files"

    /** The camera app takes the photo into a file of ours (no camera permission needed for that). */
    override suspend fun takeReceiptPhoto(): ByteArray? {
        val file = withContext(Dispatchers.IO) {
            File(activity.cacheDir, "receipts").apply { mkdirs() }.let { File(it, "receipt-photo.jpg") }.apply { delete() }
        }
        val uri = FileProvider.getUriForFile(activity, authority, file)
        pendingPhoto?.cancel()
        val done = CompletableDeferred<Boolean>().also { pendingPhoto = it }
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            error("There's no camera app to take the photo with.")
        }
        if (!done.await()) return null
        return withContext(Dispatchers.IO) {
            try {
                AndroidReceipts.normalize(activity.contentResolver, uri)
            } finally {
                file.delete()
            }
        }
    }

    override suspend fun pickReceiptPhoto(): ByteArray? {
        val uri = await { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } ?: return null
        return withContext(Dispatchers.IO) { AndroidReceipts.normalize(activity.contentResolver, uri) }
    }

    override suspend fun readText(jpeg: ByteArray): List<OcrLine> = withContext(Dispatchers.Default) { AndroidReceipts.readText(jpeg) }

    /** Opens the share sheet (Gmail, Outlook, Drive, Files…) with the file. */
    override suspend fun shareFile(name: String, bytes: ByteArray, mimeType: String): String {
        val file = withContext(Dispatchers.IO) {
            val dir = File(activity.cacheDir, "exports").apply { mkdirs() }
            // Only the latest export is kept.
            dir.listFiles()?.forEach { it.delete() }
            File(dir, name.replace('/', '-')).apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(activity, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name.substringBeforeLast('.'))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Send $name"))
        return "Opened the share menu for “$name”"
    }

    override fun syncAppName(location: SyncLocation): String? = cloudApp(location)?.first

    override fun openSyncApp(location: SyncLocation): Boolean {
        val pkg = cloudApp(location)?.second ?: return false
        val intent = activity.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        activity.startActivity(intent)
        return true
    }

    /** The cloud app behind a sync file, as (name, package). */
    private fun cloudApp(location: SyncLocation): Pair<String, String>? {
        val authority = Uri.parse(location.id).authority.orEmpty()
        return when {
            "skydrive" in authority || "onedrive" in authority -> "OneDrive" to "com.microsoft.skydrive"
            "com.google.android.apps.docs" in authority -> "Google Drive" to "com.google.android.apps.docs"
            "dropbox" in authority -> "Dropbox" to "com.dropbox.android"
            else -> null
        }
    }

    private fun displayName(uri: Uri): String =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
}
