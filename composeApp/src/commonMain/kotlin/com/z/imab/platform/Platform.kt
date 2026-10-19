package com.z.imab.platform

/** A sync file the user picked (a path on Mac, a content:// URI on Android). */
data class SyncLocation(val id: String, val displayName: String)

data class PickedFile(val name: String, val text: String)

/** File access the budget store needs; implemented per platform. */
interface Storage {
    val defaultDeviceName: String

    suspend fun readLocal(name: String): String?

    suspend fun writeLocal(name: String, text: String)

    /** Re-opens a sync location saved in settings, or null if it's no longer accessible. */
    fun restoreSyncLocation(id: String, displayName: String): SyncLocation? = SyncLocation(id, displayName)

    /**
     * Reads the sync file. The first element is the file itself ("" if empty or new); any further
     * elements are conflict copies the cloud service made next to it, which get merged in too.
     */
    suspend fun readSync(location: SyncLocation): List<String>

    suspend fun writeSync(location: SyncLocation, text: String)
}

/** System file pickers; implemented per platform. */
interface Pickers {
    suspend fun pickImportFile(): PickedFile?

    /** Lets the user choose where to create a new sync file (e.g. inside their OneDrive folder). */
    suspend fun createSyncFile(): SyncLocation?

    /** Lets the user pick a sync file that another device already created. */
    suspend fun openSyncFile(): SyncLocation?

    /** Short platform-specific hint on how to reach OneDrive / Google Drive / Dropbox in the picker. */
    val syncHint: String
}
