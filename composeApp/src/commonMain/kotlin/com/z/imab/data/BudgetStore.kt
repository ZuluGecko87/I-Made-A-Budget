package com.z.imab.data

import com.z.imab.model.BudgetData
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    val deviceId: String = "",
    val deviceName: String = "",
    val syncId: String? = null,
    val syncName: String? = null,
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** Full sync this often; new versions of the file are also picked up as soon as they arrive. */
    val syncIntervalMinutes: Int = 5,
    /** Show the "budget isn't encrypted" reminder on every screen until a passphrase is set. */
    val encryptionReminder: Boolean = true,
)

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(
        val atMillis: Long,
        val pulled: Int,
        val pushed: Boolean,
        /** Another device started a new budget (fresh start or restore) and this device switched to it. */
        val switchedBudget: Boolean = false,
        /** The cloud file held an older budget, which was archived before being replaced. */
        val archivedOld: String? = null,
    ) : SyncStatus
    data class Failed(val atMillis: Long, val message: String) : SyncStatus
    /** The sync file is encrypted with a passphrase this device doesn't have (yet). Nothing is written. */
    data object Locked : SyncStatus
}

sealed interface LockState {
    data object Unlocked : LockState
    /**
     * A budget file is encrypted and this device doesn't have its key: [local] when it's this
     * device's own copy (nothing can be shown until it's unlocked), otherwise the sync file.
     */
    data class NeedsPassphrase(val file: EncryptedFile, val local: Boolean) : LockState
}

/**
 * Owns the [Budget], saves it to app storage and syncs it with the cloud file.
 * Must be used from the main (UI) dispatcher; file IO happens inside [Storage].
 */
class BudgetStore(private val storage: Storage, private val scope: CoroutineScope) {
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _data = MutableStateFlow(BudgetData())
    val data: StateFlow<BudgetData> = _data.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _sync = MutableStateFlow<SyncStatus>(SyncStatus.NotConfigured)
    val syncStatus: StateFlow<SyncStatus> = _sync.asStateFlow()

    lateinit var budget: Budget
        private set

    private val _lock = MutableStateFlow<LockState>(LockState.Unlocked)
    val lock: StateFlow<LockState> = _lock.asStateFlow()

    /** Whether the budget files are encrypted with a passphrase. */
    private val _encrypted = MutableStateFlow(false)
    val encrypted: StateFlow<Boolean> = _encrypted.asStateFlow()

    /** Whether this device keeps the key in secure storage (otherwise the passphrase is asked each start). */
    private val _keyRemembered = MutableStateFlow(false)
    val keyRemembered: StateFlow<Boolean> = _keyRemembered.asStateFlow()

    private var vault: VaultKey? = null
    /** Keys replaced this session, still needed to read files written before the change. */
    private val oldKeys = mutableListOf<VaultKey>()
    /** This device's own copy, waiting for the passphrase. */
    private var lockedLocalText: String? = null
    /** Set once the budget is loaded; until then nothing is saved or synced. */
    private var ready = false

    private var syncLocation: SyncLocation? = null
    private val syncMutex = Mutex()
    private var saveJob: Job? = null
    private var syncJob: Job? = null
    private var dirtySinceSync = false
    /** Fingerprint of the sync file when we last read or wrote it. */
    private var lastStamp: String? = null
    private var lastSyncAt = 0L
    /** Whether the app is on screen; watching for new versions only happens then. */
    private var inForeground = true

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private fun today() = Clock.System.todayIn(TimeZone.currentSystemDefault())

    suspend fun start() {
        if (_loaded.value) return
        var s = storage.readLocal(SETTINGS_FILE)
            ?.let { runCatching { ImabJson.decodeFromString(Settings.serializer(), it) }.getOrNull() }
            ?: Settings()
        if (s.deviceId.isBlank()) s = s.copy(deviceId = Uuid.random().toString())
        if (s.deviceName.isBlank()) s = s.copy(deviceName = storage.defaultDeviceName)
        _settings.value = s
        saveSettings()

        budget = Budget(s.deviceId)
        vault = storage.loadKey()?.let(VaultKey::import)
        _encrypted.value = vault != null
        _keyRemembered.value = vault != null
        val localText = storage.readLocal(LEDGER_FILE)
        val lockedFile = localText?.let(Vault::parse)?.takeIf { f -> vault?.salt != f.salt }
        if (lockedFile != null) {
            // Encrypted, and this device doesn't have the key: wait for the passphrase (see unlock).
            lockedLocalText = localText
            _encrypted.value = true
            _lock.value = LockState.NeedsPassphrase(lockedFile, local = true)
            _loaded.value = true
            return
        }
        finishStart(localText)
    }

    private suspend fun finishStart(localText: String?) {
        localText?.let { text ->
            runCatching { Ledger.decodeFile(reveal(text)) }
                .onSuccess { budget.adopt(it) }
                // Keep an unreadable file instead of overwriting it with an empty budget.
                .onFailure { storage.writeLocal("ledger-unreadable-${now()}.json", text) }
        }
        budget.seedDefaults()
        // Older versions saved full account and card numbers; keep only the last 4 digits.
        budget.maskAccountNumbers()
        budget.onChange = {
            _data.value = budget.data
            dirtySinceSync = true
            scheduleSave()
        }
        _data.value = budget.data
        budget.processScheduled(today())
        ready = true
        saveLocal()

        val s = _settings.value
        syncLocation = s.syncId?.let { storage.restoreSyncLocation(it, s.syncName ?: it) }
        _sync.value = if (syncLocation == null) SyncStatus.NotConfigured else SyncStatus.Idle
        _loaded.value = true

        scope.launch {
            // On opening, merge with the cloud copy and always save this device's version to it.
            syncNow(forceWrite = true)
            while (isActive) {
                delay(WATCH_INTERVAL_MS)
                tick()
            }
        }
    }

    /**
     * Runs every few seconds: syncs straight away when another device's version of the file has
     * arrived, and otherwise every [Settings.syncIntervalMinutes].
     */
    internal suspend fun tick() {
        if (!ready) return
        val location = syncLocation ?: return
        if (syncMutex.isLocked) return
        val due = now() - lastSyncAt >= _settings.value.syncIntervalMinutes * 60_000L
        val arrived = inForeground &&
            runCatching { storage.syncStamp(location) }.getOrNull().let { it != null && it != lastStamp }
        if (due || arrived) {
            budget.processScheduled(today())
            syncNow()
        }
    }

    fun setSyncInterval(minutes: Int) {
        _settings.value = _settings.value.copy(syncIntervalMinutes = minutes)
        scope.launch { saveSettings() }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            saveLocal()
        }
        if (syncLocation != null) {
            syncJob?.cancel()
            syncJob = scope.launch {
                // Short pause so a burst of edits is saved as one version.
                delay(1_000)
                syncNow()
            }
        }
    }

    private suspend fun saveLocal() {
        if (ready) storage.writeLocal(LEDGER_FILE, protect(budget.ledger.encode()))
    }

    /** [text] encrypted when a passphrase is set; as is otherwise. */
    private fun protect(text: String): String = vault?.let { Vault.seal(text, it) } ?: text

    /** The plaintext of a stored file, with whichever of this device's keys fits it. */
    private fun reveal(text: String): String {
        val salt = Vault.parse(text)?.salt
        return Vault.reveal(text, (listOfNotNull(vault) + oldKeys).firstOrNull { it.salt == salt } ?: vault)
    }

    // ---------------------------------------------------------------- encryption

    /**
     * Unlocks with [passphrase]: this device's own copy (then loading finishes) or the sync file
     * another device encrypted. Returns false if the passphrase is wrong. The key is kept in the
     * system's secure storage so it isn't asked again on this device.
     */
    suspend fun unlock(passphrase: String): Boolean {
        val state = _lock.value as? LockState.NeedsPassphrase ?: return true
        val key = withContext(Dispatchers.Default) { Vault.unlock(state.file, passphrase) } ?: return false
        useKey(key)
        _lock.value = LockState.Unlocked
        if (state.local) {
            val text = lockedLocalText
            lockedLocalText = null
            finishStart(text)
        } else {
            // This device's copy may still be under an older passphrase; save it under this one.
            saveLocal()
            syncNow(forceWrite = true)
        }
        return true
    }

    /**
     * Encrypts the budget with [passphrase] (setting it, or changing it). Every file is rewritten
     * encrypted: this device's copy, its archives, and the sync file. Other devices ask for the new
     * passphrase the next time they sync.
     */
    suspend fun setPassphrase(passphrase: String) {
        check(ready) { "The budget isn't loaded." }
        require(passphrase.length >= Vault.MIN_PASSPHRASE) { "Use at least ${Vault.MIN_PASSPHRASE} characters." }
        val key = withContext(Dispatchers.Default) { Vault.newKey(passphrase) }
        useKey(key)
        saveLocal()
        // Archives kept in app storage from before are encrypted too.
        for (name in storage.listLocal("")) {
            if (name == LEDGER_FILE || name == SETTINGS_FILE || !name.endsWith(".json")) continue
            val text = storage.readLocal(name) ?: continue
            if (!Vault.isEncrypted(text)) storage.writeLocal(name, protect(text))
        }
        syncNow(forceWrite = true)
    }

    private suspend fun useKey(key: VaultKey) {
        vault?.takeIf { it.salt != key.salt }?.let(oldKeys::add)
        vault = key
        _encrypted.value = true
        _keyRemembered.value = storage.saveKey(key.export())
    }

    private suspend fun saveSettings() =
        storage.writeLocal(SETTINGS_FILE, ImabJson.encodeToString(Settings.serializer(), _settings.value))

    fun setEncryptionReminder(show: Boolean) {
        _settings.value = _settings.value.copy(encryptionReminder = show)
        scope.launch { saveSettings() }
    }

    fun setTheme(theme: String) {
        _settings.value = _settings.value.copy(theme = theme)
        scope.launch { saveSettings() }
    }

    fun setDeviceName(name: String) {
        _settings.value = _settings.value.copy(deviceName = name)
        scope.launch { saveSettings() }
    }

    /** Connects to a sync file and merges with whatever is already in it. */
    fun connectSync(location: SyncLocation) {
        syncLocation = location
        _settings.value = _settings.value.copy(syncId = location.id, syncName = location.displayName)
        _sync.value = SyncStatus.Idle
        scope.launch {
            saveSettings()
            syncNow()
        }
    }

    fun disconnectSync() {
        syncLocation = null
        _settings.value = _settings.value.copy(syncId = null, syncName = null)
        _sync.value = SyncStatus.NotConfigured
        scope.launch { saveSettings() }
    }

    fun requestSync() {
        scope.launch { syncNow() }
    }

    /** Call when the app goes to the background (phone). */
    fun onPause() {
        inForeground = false
    }

    /** Call when the app comes back to the foreground. */
    fun onResume() {
        inForeground = true
        if (!ready) return
        budget.processScheduled(today())
        requestSync()
    }

    suspend fun syncNow(forceWrite: Boolean = false) {
        if (!ready || syncLocation == null) return
        syncMutex.withLock { syncLocked(forceWrite) }
    }

    private suspend fun syncLocked(forceWrite: Boolean = false) {
        val location = syncLocation ?: return
        _sync.value = SyncStatus.Syncing
        try {
            val stampBefore = runCatching { storage.syncStamp(location) }.getOrNull()
            val files = storage.readSync(location)
            val mainRaw = files.firstOrNull().orEmpty()
            // Never overwrite a file we can't read: it may not be an IMAB file at all, or it may be
            // encrypted with a passphrase this device doesn't have (VaultLockedException).
            val mainText = if (mainRaw.isBlank()) "" else reveal(mainRaw)
            val remoteFile = if (mainText.isBlank()) null else Ledger.decodeFile(mainText)
            var switched = false
            var archivedOld: String? = null
            if (remoteFile != null && budget.ledger.isOlderThan(remoteFile)) {
                // Another device started a new budget: switch to it, keeping a copy of what this device had.
                storage.writeLocal(archiveName("replaced", now()), protect(budget.ledger.encode()))
                budget.adopt(remoteFile)
                switched = true
            } else if (remoteFile != null && remoteFile.budgetId != budget.ledger.budgetId &&
                remoteFile.budgetId != alreadyArchived
            ) {
                // This device's budget is newer; keep the old one from the cloud file before replacing it.
                val name = archiveName(syncBaseName(location), remoteFile.startedAt.takeIf { it > 0 } ?: now())
                val archived = protect(mainText)
                storage.writeLocal(name, archived)
                if (storage.writeArchive(location, name, archived)) archivedOld = name
            }
            val sameBudget = remoteFile?.budgetId == budget.ledger.budgetId
            val remote = if (sameBudget) remoteFile!!.ops else emptyList()
            // Conflict copies only count if they're copies of this budget.
            val copies = files.drop(1).mapNotNull { runCatching { Ledger.decodeFile(reveal(it)) }.getOrNull() }
                .filter { it.budgetId == budget.ledger.budgetId }.flatMap { it.ops }

            dirtySinceSync = false
            val pulled = if (switched) remoteFile!!.ops.size else budget.merge(remote + copies)
            if (pulled > 0) budget.processScheduled(today())
            // A device on an older version may have added a full account number.
            budget.maskAccountNumbers()

            // Rewrite when the file is missing changes, holds a different budget, has leftovers from an
            // untruncated write, or isn't encrypted yet although this budget is.
            val push = forceWrite || !sameBudget || !budget.ledger.sameAs(remote) ||
                (mainRaw.isNotBlank() && Ledger.hasLeftovers(mainRaw)) ||
                (vault != null && mainRaw.isNotBlank() && !Vault.isEncrypted(mainRaw))
            if (push) storage.writeSync(location, protect(budget.ledger.encode()))
            if (vault != null) {
                // Conflict copies of this budget were merged above; don't leave them readable.
                storage.rewriteConflictCopies(location) { text ->
                    if (Vault.isEncrypted(text)) null
                    else runCatching { Ledger.decodeFile(text) }.getOrNull()
                        ?.takeIf { it.budgetId == budget.ledger.budgetId }?.let { protect(text) }
                }
            }
            // Remember the file as we left it, so our own write doesn't look like a new arrival.
            lastStamp = if (push) runCatching { storage.syncStamp(location) }.getOrNull() else stampBefore
            lastSyncAt = now()
            _sync.value = SyncStatus.Synced(now(), pulled, push, switched, archivedOld)
        } catch (e: VaultLockedException) {
            // Another device encrypted the budget (or changed the passphrase): ask for it, write nothing.
            lastSyncAt = now()
            _lock.value = LockState.NeedsPassphrase(e.file, local = false)
            _sync.value = SyncStatus.Locked
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Count a failure as an attempt so it's retried at the normal pace, not every few seconds.
            lastSyncAt = now()
            _sync.value = SyncStatus.Failed(now(), e.message ?: e::class.simpleName ?: "Sync failed")
        }
    }

    // ---------------------------------------------------------------- archives and starting over

    /** Budget id whose cloud file was archived by [archiveCurrent], so sync doesn't archive it twice. */
    private var alreadyArchived: String? = null

    data class Archive(val name: String, val text: String, val savedTo: String?)

    /**
     * Syncs (so changes from other devices are included), then saves the whole budget as a dated
     * archive: next to the sync file where possible, and always in app storage. [Archive.savedTo]
     * is null when it couldn't go next to the sync file, so the caller can offer to save it.
     */
    suspend fun archiveCurrent(): Archive {
        syncNow()
        val text = protect(budget.ledger.encode())
        val location = syncLocation
        val name = archiveName(location?.let(::syncBaseName) ?: "IMAB-budget", now())
        storage.writeLocal(name, text)
        val savedTo = location?.let { if (storage.writeArchive(it, name, text)) folderOf(it) + name else null }
        alreadyArchived = budget.ledger.budgetId
        return Archive(name, text, savedTo)
    }

    /** See [Budget.freshStart]. Call [archiveCurrent] first. */
    fun freshStart(keepBalances: Boolean) {
        budget.freshStart(keepBalances, today())
        afterReset()
    }

    /** See [Budget.startFromScratch]. Call [archiveCurrent] first. */
    fun startFromScratch() {
        budget.startFromScratch()
        afterReset()
    }

    /** Brings back an archived budget. Call [archiveCurrent] first. */
    fun restore(file: LedgerFile) {
        budget.restore(file)
        afterReset()
    }

    private fun afterReset() {
        _data.value = budget.data
        scope.launch {
            saveLocal()
            syncNow(forceWrite = true)
        }
    }

    /** Archives kept in app storage, newest first. */
    suspend fun localArchives(): List<String> = storage.listLocal("").filter { it.contains(" archive ") }

    suspend fun readLocalArchive(name: String): String? = storage.readLocal(name)

    /**
     * Reads an archived budget, decrypting it with this device's key or, for an archive encrypted
     * with an older passphrase, with [passphrase]. Throws [VaultLockedException] when it needs one.
     */
    suspend fun decodeArchive(text: String, passphrase: String? = null): LedgerFile {
        val file = Vault.parse(text) ?: return Ledger.decodeFile(text)
        val key = vault?.takeIf { it.salt == file.salt }
            ?: passphrase?.let { p -> withContext(Dispatchers.Default) { Vault.unlock(file, p) } }
            ?: throw VaultLockedException(file)
        return Ledger.decodeFile(Vault.open(file, key))
    }

    private fun syncBaseName(location: SyncLocation): String =
        location.displayName.substringAfterLast('/').substringAfterLast("› ").trim().removeSuffix(".json")

    private fun folderOf(location: SyncLocation): String =
        location.displayName.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    /** "<base> archive 2026-10-19 1834.json" in local time. */
    private fun archiveName(base: String, atMillis: Long): String {
        val t = kotlin.time.Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "$base archive ${t.year}-${two(t.monthNumber)}-${two(t.dayOfMonth)} ${two(t.hour)}${two(t.minute)}.json"
    }

    /** Saves everything and makes a last sync attempt; used when the app is closing. */
    suspend fun flush() {
        if (!ready) return
        saveJob?.cancel()
        saveLocal()
        if (syncLocation != null && dirtySinceSync && syncMutex.tryLock()) {
            try {
                withTimeoutOrNull(8_000) { syncLocked() }
            } finally {
                syncMutex.unlock()
            }
        }
    }

    companion object {
        const val LEDGER_FILE = "ledger.json"
        const val SETTINGS_FILE = "settings.json"
        /** How often to check whether a new version of the sync file has arrived. */
        const val WATCH_INTERVAL_MS = 5_000L
        const val DEFAULT_SYNC_FILE_NAME = "IMAB-budget.json"
    }
}
