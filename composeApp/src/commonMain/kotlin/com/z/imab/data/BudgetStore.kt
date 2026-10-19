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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
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
)

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val atMillis: Long, val pulled: Int, val pushed: Boolean) : SyncStatus
    data class Failed(val atMillis: Long, val message: String) : SyncStatus
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
        storage.readLocal(LEDGER_FILE)?.let { text ->
            runCatching { Ledger.decode(text) }
                .onSuccess { budget.merge(it) }
                // Keep an unreadable file instead of overwriting it with an empty budget.
                .onFailure { storage.writeLocal("ledger-unreadable-${now()}.json", text) }
        }
        budget.seedDefaults()
        budget.onChange = {
            _data.value = budget.data
            dirtySinceSync = true
            scheduleSave()
        }
        _data.value = budget.data
        budget.processScheduled(today())
        saveLocal()

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

    private suspend fun saveLocal() = storage.writeLocal(LEDGER_FILE, budget.ledger.encode())

    private suspend fun saveSettings() =
        storage.writeLocal(SETTINGS_FILE, ImabJson.encodeToString(Settings.serializer(), _settings.value))

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
        if (!_loaded.value) return
        budget.processScheduled(today())
        requestSync()
    }

    suspend fun syncNow(forceWrite: Boolean = false) {
        if (syncLocation == null) return
        syncMutex.withLock { syncLocked(forceWrite) }
    }

    private suspend fun syncLocked(forceWrite: Boolean = false) {
        val location = syncLocation ?: return
        _sync.value = SyncStatus.Syncing
        try {
            val stampBefore = runCatching { storage.syncStamp(location) }.getOrNull()
            val files = storage.readSync(location)
            val mainText = files.firstOrNull().orEmpty()
            // Never overwrite a file we can't read: it may not be an IMAB file at all.
            val remote = if (mainText.isBlank()) emptyList() else Ledger.decode(mainText)
            val copies = files.drop(1).flatMap { runCatching { Ledger.decode(it) }.getOrElse { emptyList() } }

            dirtySinceSync = false
            val pulled = budget.merge(remote + copies)
            if (pulled > 0) budget.processScheduled(today())

            // Rewrite when the file is missing changes, or when it has leftovers from an untruncated write.
            val push = forceWrite || !budget.ledger.sameAs(remote) || (mainText.isNotBlank() && Ledger.hasLeftovers(mainText))
            if (push) storage.writeSync(location, budget.ledger.encode())
            // Remember the file as we left it, so our own write doesn't look like a new arrival.
            lastStamp = if (push) runCatching { storage.syncStamp(location) }.getOrNull() else stampBefore
            lastSyncAt = now()
            _sync.value = SyncStatus.Synced(now(), pulled, push)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Count a failure as an attempt so it's retried at the normal pace, not every few seconds.
            lastSyncAt = now()
            _sync.value = SyncStatus.Failed(now(), e.message ?: e::class.simpleName ?: "Sync failed")
        }
    }

    /** Saves everything and makes a last sync attempt; used when the app is closing. */
    suspend fun flush() {
        if (!_loaded.value) return
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
