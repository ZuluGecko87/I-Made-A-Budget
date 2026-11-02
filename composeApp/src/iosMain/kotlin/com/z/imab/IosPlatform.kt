package com.z.imab

import com.z.imab.platform.Native
import com.z.imab.platform.PickedFile
import com.z.imab.platform.Pickers
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import com.z.imab.platform.toByteArray
import com.z.imab.platform.toNSData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingWithoutChanges
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * Budget files on iPhone. The budget itself lives in the app's own storage; the sync file is one
 * the user picked in the Files app (iCloud Drive, OneDrive, Google Drive, Dropbox…). Access to it
 * is kept as a bookmark, saved as the [SyncLocation] id, and every read and write goes through a
 * file coordinator so the cloud app sees it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosStorage : Storage {
    override val defaultDeviceName: String = UIDevice.currentDevice.name

    private val dir: String by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        "$base/IMAB".also { NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null) }
    }

    private fun localUrl(name: String) = NSURL.fileURLWithPath("$dir/$name")

    override suspend fun readLocal(name: String): String? = withContext(Dispatchers.IO) {
        NSData.dataWithContentsOfURL(localUrl(name))?.toByteArray()?.decodeToString()
    }

    override suspend fun writeLocal(name: String, text: String): Unit = withContext(Dispatchers.IO) {
        check(text.encodeToByteArray().toNSData().writeToURL(localUrl(name), atomically = true)) { "Couldn't save $name." }
    }

    override suspend fun listLocal(prefix: String): List<String> = withContext(Dispatchers.IO) {
        val fm = NSFileManager.defaultManager
        (fm.contentsOfDirectoryAtPath(dir, null) as? List<*>).orEmpty().filterIsInstance<String>()
            .filter { it.startsWith(prefix) }
            .sortedByDescending { modified("$dir/$it") }
    }

    private fun modified(path: String): Double =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 ?: 0.0

    override suspend fun loadKey(): String? = Native.services.loadKey()

    override suspend fun saveKey(value: String): Boolean = Native.services.saveKey(value)

    override suspend fun deleteKey() = Native.services.deleteKey()

    override fun restoreSyncLocation(id: String, displayName: String): SyncLocation? =
        resolve(id)?.let { SyncLocation(id, displayName) }

    /** The picked file's URL from its saved bookmark, or null if it's gone or access was lost. */
    private fun resolve(id: String): NSURL? = memScoped {
        val data = NSData.create(base64EncodedString = id, options = 0u) ?: return null
        val stale = alloc<kotlinx.cinterop.BooleanVar>()
        NSURL.URLByResolvingBookmarkData(data, options = 0u, relativeToURL = null, bookmarkDataIsStale = stale.ptr, error = null)
    }

    /** Runs [block] with access to the picked file, coordinated with the app that syncs it. */
    private fun <T> withAccess(location: SyncLocation, write: Boolean, block: (NSURL) -> T): T {
        val url = resolve(location.id) ?: error("Can't open the sync file. Pick it again in Settings.")
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            var result: Result<T>? = null
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                val coordinator = NSFileCoordinator(filePresenter = null)
                if (write) {
                    coordinator.coordinateWritingItemAtURL(url, NSFileCoordinatorWritingForReplacing, err.ptr) { u ->
                        result = runCatching { block(u ?: url) }
                    }
                } else {
                    coordinator.coordinateReadingItemAtURL(url, NSFileCoordinatorReadingWithoutChanges, err.ptr) { u ->
                        result = runCatching { block(u ?: url) }
                    }
                }
                err.value?.let { error(it.localizedDescription) }
            }
            return (result ?: error("The sync file couldn't be reached.")).getOrThrow()
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    override suspend fun readSync(location: SyncLocation): List<String> = withContext(Dispatchers.IO) {
        listOf(withAccess(location, write = false) { u -> NSData.dataWithContentsOfURL(u)?.toByteArray()?.decodeToString().orEmpty() })
    }

    override suspend fun writeSync(location: SyncLocation, text: String): Unit = withContext(Dispatchers.IO) {
        withAccess(location, write = true) { u ->
            check(text.encodeToByteArray().toNSData().writeToURL(u, atomically = true)) { "Couldn't write the sync file." }
        }
    }

    override suspend fun syncStamp(location: SyncLocation): String? = withContext(Dispatchers.IO) {
        runCatching {
            withAccess(location, write = false) { u ->
                val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(u.path ?: return@withAccess null, null)
                val date = (attrs?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970
                val size = (attrs?.get(NSFileSize) as? NSNumber)?.longLongValue
                "$date:$size"
            }
        }.getOrNull()
    }
}

/** A saved, reusable reference to a file the user picked, as text. */
@OptIn(ExperimentalForeignApi::class)
private fun bookmark(url: NSURL): String? {
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        return url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
            ?.base64EncodedStringWithOptions(0u)
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

/** The Files app's pickers. [host] gives the screen to show them over. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPickers(private val host: () -> UIViewController?) : Pickers {
    override val syncHint =
        "In the Files picker, choose iCloud Drive, or Browse → Locations for OneDrive, Google Drive or Dropbox " +
            "(their apps must be installed and turned on under Locations)."

    /** Kept while a picker is open: UIKit holds its delegate weakly. */
    private var delegate: NSObject? = null

    private suspend fun pick(make: () -> UIDocumentPickerViewController): NSURL? = suspendCancellableCoroutine { cont ->
        val picker = make()
        val d = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                delegate = null
                if (cont.isActive) cont.resume(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                delegate = null
                if (cont.isActive) cont.resume(null)
            }
        }
        delegate = d
        picker.delegate = d
        val top = host() ?: run { delegate = null; cont.resume(null); return@suspendCancellableCoroutine }
        top.presentViewController(picker, animated = true, completion = null)
    }

    private fun readPicked(url: NSURL): PickedFile? {
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val text = NSData.dataWithContentsOfURL(url)?.toByteArray()?.decodeToString() ?: return null
            return PickedFile(url.lastPathComponent ?: "file", text)
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    override suspend fun pickImportFile(): PickedFile? =
        pick { UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData, UTTypeItem), asCopy = true) }?.let(::readPicked)

    override suspend fun pickArchive(): PickedFile? =
        pick { UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON, UTTypeData), asCopy = true) }?.let(::readPicked)

    override suspend fun openSyncFile(): SyncLocation? {
        val url = pick { UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON, UTTypeData), asCopy = false) } ?: return null
        val id = bookmark(url) ?: return null
        return SyncLocation(id, url.lastPathComponent ?: "budget")
    }

    /** Saves an empty budget file where the user chooses, then keeps access to it there. */
    override suspend fun createSyncFile(): SyncLocation? {
        val temp = NSURL.fileURLWithPath(NSTemporaryDirectory() + "IMAB-budget.json")
        "".encodeToByteArray().toNSData().writeToURL(temp, atomically = true)
        val url = pick { UIDocumentPickerViewController(forExportingURLs = listOf(temp), asCopy = false) } ?: return null
        val id = bookmark(url) ?: return null
        return SyncLocation(id, url.lastPathComponent ?: "IMAB-budget.json")
    }

    override suspend fun saveFile(suggestedName: String, text: String): String? {
        val temp = NSURL.fileURLWithPath(NSTemporaryDirectory() + suggestedName)
        text.encodeToByteArray().toNSData().writeToURL(temp, atomically = true)
        val url = pick { UIDocumentPickerViewController(forExportingURLs = listOf(temp), asCopy = true) } ?: return null
        return url.lastPathComponent
    }
}

/** The screen pickers are shown over: the front-most one. */
fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}
