package com.z.imab

import com.z.imab.data.BudgetStore
import com.z.imab.platform.PickedFile
import com.z.imab.platform.Pickers
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val home = File(System.getProperty("user.home"))

class DesktopStorage : Storage {
    private val dir: File = run {
        // IMAB_DATA_DIR lets you run a throwaway copy without touching your real budget.
        System.getenv("IMAB_DATA_DIR")?.let { return@run File(it).apply { mkdirs() } }
        val mac = File(home, "Library/Application Support")
        (if (mac.isDirectory) File(mac, "IMAB") else File(home, ".imab")).apply { mkdirs() }
    }

    override val defaultDeviceName: String =
        runCatching { java.net.InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrNull() ?: "Mac"

    override suspend fun readLocal(name: String): String? = withContext(Dispatchers.IO) {
        File(dir, name).takeIf { it.isFile }?.readText()
    }

    override suspend fun writeLocal(name: String, text: String) = withContext(Dispatchers.IO) {
        atomicWrite(File(dir, name), text)
    }

    override fun restoreSyncLocation(id: String, displayName: String): SyncLocation =
        SyncLocation(id, displayName)

    override suspend fun readSync(location: SyncLocation): List<String> = withContext(Dispatchers.IO) {
        val file = File(location.id)
        val main = if (file.isFile) file.readText() else ""
        listOf(main) + conflictCopies(file).mapNotNull { runCatching { it.readText() }.getOrNull() }
    }

    override suspend fun syncStamp(location: SyncLocation): String = withContext(Dispatchers.IO) {
        val file = File(location.id)
        (listOf(file) + conflictCopies(file)).joinToString("|") { "${it.name}:${it.lastModified()}:${it.length()}" }
    }

    override suspend fun writeSync(location: SyncLocation, text: String) = withContext(Dispatchers.IO) {
        val file = File(location.id)
        if (file.parentFile?.isDirectory != true) error("Folder ${file.parent} isn't available. Is the cloud drive signed in?")
        atomicWrite(file, text)
    }

    /**
     * Cloud services save conflicting edits next to the original, e.g. "IMAB-budget-MacBook.json"
     * (OneDrive), "IMAB-budget (conflicted copy).json" (Dropbox), "IMAB-budget (1).json" (Google Drive).
     */
    private fun conflictCopies(file: File): List<File> {
        val base = file.nameWithoutExtension
        return file.parentFile?.listFiles { f ->
            f.isFile && f != file && f.extension == "json" && f.name.startsWith(base)
        }?.toList().orEmpty()
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

class DesktopPickers : Pickers {
    /** Cloud drives on macOS mount under ~/Library/CloudStorage (OneDrive, Google Drive, Dropbox). */
    private val cloudRoot: File? = File(home, "Library/CloudStorage").takeIf { it.isDirectory }

    private fun defaultDir(): File = cloudRoot?.listFiles()?.firstOrNull { it.name.startsWith("OneDrive") }
        ?: cloudRoot?.listFiles()?.firstOrNull { it.isDirectory }
        ?: home

    override val supportsFileDrop = true

    override val syncHint: String =
        "On a Mac, OneDrive, Google Drive and Dropbox appear in the sidebar of the file dialog (or under ~/Library/CloudStorage)."

    override suspend fun pickImportFile(): PickedFile? {
        val dialog = FileDialog(null as Frame?, "Choose a bank file (.qbo, .qfx, .ofx)", FileDialog.LOAD)
        dialog.directory = File(home, "Downloads").takeIf { it.isDirectory }?.path ?: home.path
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("qbo", "qfx", "ofx") }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val file = File(dialog.directory, name)
        return withContext(Dispatchers.IO) { PickedFile(file.name, file.readText(Charsets.UTF_8)) }
    }

    override suspend fun createSyncFile(): SyncLocation? {
        val dialog = FileDialog(null as Frame?, "Save the IMAB sync file in OneDrive, Google Drive or Dropbox", FileDialog.SAVE)
        dialog.directory = defaultDir().path
        dialog.file = BudgetStore.DEFAULT_SYNC_FILE_NAME
        dialog.isVisible = true
        var name = dialog.file ?: return null
        if (!name.endsWith(".json")) name += ".json"
        val file = File(dialog.directory, name)
        // If they picked an existing IMAB file it'll be merged, not overwritten.
        return SyncLocation(file.absolutePath, displayPath(file))
    }

    override suspend fun openSyncFile(): SyncLocation? {
        val dialog = FileDialog(null as Frame?, "Choose the existing IMAB sync file", FileDialog.LOAD)
        dialog.directory = defaultDir().path
        dialog.setFilenameFilter { _, name -> name.endsWith(".json") }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val file = File(dialog.directory, name)
        return SyncLocation(file.absolutePath, displayPath(file))
    }

    private fun displayPath(file: File): String {
        val cloud = cloudRoot?.path
        return if (cloud != null && file.path.startsWith(cloud)) file.path.removePrefix("$cloud/") else file.path
    }
}
