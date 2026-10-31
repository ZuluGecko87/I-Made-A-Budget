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
private val osName = System.getProperty("os.name").orEmpty().lowercase()
internal val isMac = osName.contains("mac")
internal val isWindows = osName.contains("windows")

/**
 * Where IMAB keeps its own copy of the budget: ~/Library/Application Support/IMAB on a Mac,
 * %APPDATA%\IMAB on Windows, ~/.imab elsewhere.
 */
internal fun appDataDir(): File = when {
    isMac -> File(home, "Library/Application Support/IMAB")
    isWindows -> File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Roaming").path, "IMAB")
    else -> File(home, ".imab")
}

/**
 * Folders of the cloud services' desktop apps, OneDrive first. On a Mac they mount under
 * ~/Library/CloudStorage (Dropbox may also be ~/Dropbox). On Windows OneDrive sets %OneDrive%,
 * Dropbox uses %USERPROFILE%\Dropbox, and Google Drive mounts a drive letter with "My Drive".
 */
internal fun cloudFolders(): List<File> {
    val found = mutableListOf<File>()
    if (isMac) {
        File(home, "Library/CloudStorage").listFiles { f -> f.isDirectory }?.sortedBy { !it.name.startsWith("OneDrive") }?.let(found::addAll)
    }
    if (isWindows) {
        listOf("OneDrive", "OneDriveConsumer", "OneDriveCommercial").mapNotNull { System.getenv(it) }.map(::File).let(found::addAll)
        File.listRoots().map { File(it, "My Drive") }.let(found::addAll)
    }
    found += File(home, "OneDrive")
    found += File(home, "Dropbox")
    return found.filter { it.isDirectory }.distinctBy { it.absolutePath }
}

/**
 * Windows ignores FileDialog's filename filter; a pattern in the file name box filters instead.
 * Returns the name to start the dialog with.
 */
private fun FileDialog.filterTo(vararg extensions: String) {
    setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in extensions }
    if (isWindows) file = extensions.joinToString(";") { "*.$it" }
}

class DesktopStorage : Storage {
    private val dir: File = run {
        // IMAB_DATA_DIR lets you run a throwaway copy without touching your real budget.
        System.getenv("IMAB_DATA_DIR")?.let { return@run File(it).apply { mkdirs() } }
        appDataDir().apply { mkdirs() }
    }

    override val defaultDeviceName: String =
        runCatching { java.net.InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrNull() ?: if (isMac) "Mac" else "Computer"

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

    override suspend fun writeArchive(location: SyncLocation, name: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val folder = File(location.id).parentFile ?: return@withContext false
        if (!folder.isDirectory) return@withContext false
        atomicWrite(File(folder, name), text)
        true
    }

    override suspend fun listLocal(prefix: String): List<String> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.sortedByDescending { it.lastModified() }?.map { it.name }.orEmpty()
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
            f.isFile && f != file && f.extension == "json" && f.name.startsWith(base) && !isArchive(f.name)
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
    private val clouds: List<File> = cloudFolders()

    private fun defaultDir(): File = clouds.firstOrNull() ?: home

    override val supportsFileDrop = true

    override val syncHint: String = when {
        isMac -> "On a Mac, OneDrive, Google Drive and Dropbox appear in the sidebar of the file dialog (or under ~/Library/CloudStorage)."
        isWindows -> "On Windows, OneDrive and Dropbox are folders in File Explorer's sidebar, and Google Drive is a drive letter with \"My Drive\". Their desktop apps must be installed and signed in."
        else -> "Choose a folder that OneDrive, Google Drive or Dropbox syncs."
    }

    override suspend fun saveFile(suggestedName: String, text: String): String? {
        val dialog = FileDialog(null as Frame?, "Save archive", FileDialog.SAVE)
        dialog.directory = defaultDir().path
        dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val file = File(dialog.directory, name)
        withContext(Dispatchers.IO) { file.writeText(text) }
        return displayPath(file)
    }

    override suspend fun pickArchive(): PickedFile? {
        val dialog = FileDialog(null as Frame?, "Choose an IMAB archive to restore", FileDialog.LOAD)
        dialog.directory = lastSyncFolder ?: defaultDir().path
        dialog.filterTo("json")
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val file = File(dialog.directory, name)
        return withContext(Dispatchers.IO) { PickedFile(file.name, file.readText()) }
    }

    /** Folder of the sync file, so the archive picker opens where archives are saved. */
    var lastSyncFolder: String? = null

    override suspend fun pickImportFile(): PickedFile? {
        val dialog = FileDialog(null as Frame?, "Choose a bank file (.qbo, .qfx, .ofx)", FileDialog.LOAD)
        dialog.directory = File(home, "Downloads").takeIf { it.isDirectory }?.path ?: home.path
        dialog.filterTo("qbo", "qfx", "ofx")
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
        dialog.filterTo("json")
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val file = File(dialog.directory, name)
        return SyncLocation(file.absolutePath, displayPath(file))
    }

    /** "OneDrive/Budget/IMAB-budget.json" rather than the full path, when it's in a cloud folder. */
    private fun displayPath(file: File): String {
        val cloud = clouds.firstOrNull { file.path.startsWith(it.path + File.separator) } ?: return file.path
        // On a Mac the folder is "OneDrive-Personal" under CloudStorage; elsewhere name it after its folder.
        return cloud.name + File.separator + file.path.removePrefix(cloud.path + File.separator)
    }
}

/** Archives are saved next to the sync file; they must never be merged in as conflict copies. */
internal fun isArchive(name: String) = name.contains(" archive ")
