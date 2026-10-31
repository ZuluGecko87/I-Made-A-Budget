package com.z.imab

import com.z.imab.data.BudgetStore
import com.z.imab.data.EncryptedFile
import com.z.imab.data.Ledger
import com.z.imab.data.LockState
import com.z.imab.data.SyncStatus
import com.z.imab.data.Vault
import com.z.imab.data.VaultLockedException
import com.z.imab.data.ImabJson
import com.z.imab.importer.AccountKeys
import com.z.imab.importer.OfxParser
import com.z.imab.model.Account
import com.z.imab.model.Transaction
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Encrypted budget files, and account numbers kept out of them. */
class EncryptionTest {
    private class Cloud {
        var main = ""
        var version = 0
        val archives = LinkedHashMap<String, String>()
        val copies = mutableListOf<String>()
    }

    private class Device(val cloud: Cloud) : Storage {
        override val defaultDeviceName = "test"
        val local = HashMap<String, String>()
        var key: String? = null
        override suspend fun readLocal(name: String) = local[name]
        override suspend fun writeLocal(name: String, text: String) { local[name] = text }
        override suspend fun readSync(location: SyncLocation) = listOf(cloud.main) + cloud.copies
        override suspend fun rewriteConflictCopies(location: SyncLocation, transform: (String) -> String?) {
            cloud.copies.indices.forEach { i -> transform(cloud.copies[i])?.let { cloud.copies[i] = it } }
        }
        override suspend fun writeSync(location: SyncLocation, text: String) { cloud.main = text; cloud.version++ }
        override suspend fun syncStamp(location: SyncLocation) = "v${cloud.version}"
        override suspend fun writeArchive(location: SyncLocation, name: String, text: String): Boolean { cloud.archives[name] = text; return true }
        override suspend fun listLocal(prefix: String) = local.keys.filter { it.startsWith(prefix) }
        override suspend fun loadKey() = key
        override suspend fun saveKey(value: String): Boolean { key = value; return true }
        override suspend fun deleteKey() { key = null }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val loc = SyncLocation("cloud", "Budget/IMAB-budget.json")

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun open(storage: Device, connect: Boolean = true) = BudgetStore(storage, scope).also {
        runBlocking { it.start() }
        if (connect && it.lock.value == LockState.Unlocked) it.connectSync(loc)
    }

    private val passphrase = "correct horse battery"

    @Test
    fun sealedTextHidesEverythingAndDetectsTampering() {
        val key = Vault.newKey(passphrase)
        val sealed = Vault.seal("""{"payee":"Corner Bakery","amount":-1234}""", key)
        assertFalse(sealed.contains("Corner Bakery"))
        assertFalse(sealed.contains("1234"))
        val file = assertNotNull(Vault.parse(sealed))
        assertEquals(Vault.ITERATIONS, file.iterations)
        assertEquals("""{"payee":"Corner Bakery","amount":-1234}""", Vault.reveal(sealed, key))

        // Two seals of the same text differ (fresh IV each time).
        assertFalse(sealed == Vault.seal("""{"payee":"Corner Bakery","amount":-1234}""", key))
        // Wrong passphrase: no key. No key at all: locked.
        assertNull(Vault.unlock(file, "wrong passphrase"))
        assertNotNull(Vault.unlock(file, passphrase))
        assertFailsWith<VaultLockedException> { Vault.reveal(sealed, null) }
        // Changing the ciphertext or the header is detected.
        val data = file.data.toCharArray().also { it[30] = if (it[30] == 'A') 'B' else 'A' }.concatToString()
        assertFailsWith<VaultLockedException> { Vault.reveal(ImabJson.encodeToString(EncryptedFile.serializer(), file.copy(data = data)), key) }
        assertFailsWith<VaultLockedException> { Vault.reveal(ImabJson.encodeToString(EncryptedFile.serializer(), file.copy(version = 2)), key) }
    }

    @Test
    fun settingAPassphraseEncryptsEveryFileAndOtherDevicesAskForIt() = runBlocking<Unit> {
        val cloud = Cloud()
        val macDisk = Device(cloud)
        val mac = open(macDisk)
        mac.budget.saveAccount(Account("chk", "Checking"))
        mac.budget.saveTransaction(Transaction("t1", "chk", LocalDate(2026, 10, 1), -1234, "Corner Bakery"))
        mac.syncNow()
        mac.archiveCurrent()
        assertTrue(cloud.main.contains("Corner Bakery"), "not encrypted yet")

        mac.setPassphrase(passphrase)
        assertTrue(mac.encrypted.value)
        // The sync file, this device's copy and its archives are all encrypted.
        for (text in listOf(cloud.main) + macDisk.local.filterKeys { it != BudgetStore.SETTINGS_FILE }.values) {
            assertTrue(Vault.isEncrypted(text), text.take(60))
            assertFalse(text.contains("Corner Bakery"))
        }
        assertNotNull(macDisk.key, "the key is kept in secure storage")

        // A phone that already synced the plain budget: it can't read the new file and doesn't overwrite it.
        val phoneDisk = Device(cloud)
        val phone = open(phoneDisk)
        val before = cloud.main
        phone.syncNow()
        assertIs<SyncStatus.Locked>(phone.syncStatus.value)
        assertEquals(before, cloud.main, "a file it can't read is never overwritten")
        assertFalse(phone.unlock("wrong passphrase"))
        assertTrue(phone.unlock(passphrase))
        assertEquals(LockState.Unlocked, phone.lock.value)
        assertEquals("Corner Bakery", phone.data.value.transactions["t1"]?.payee)

        // Changes keep flowing, encrypted.
        phone.budget.saveTransaction(Transaction("t2", "chk", LocalDate(2026, 10, 2), -500, "Gas Station"))
        phone.syncNow()
        assertFalse(cloud.main.contains("Gas Station"))
        mac.syncNow()
        assertEquals("Gas Station", mac.data.value.transactions["t2"]?.payee)

        // Restarting with the key in secure storage opens without asking.
        val again = open(macDisk)
        assertEquals(LockState.Unlocked, again.lock.value)
        assertEquals("Corner Bakery", again.data.value.transactions["t1"]?.payee)
    }

    @Test
    fun aDeviceWithoutTheKeyAsksBeforeShowingItsOwnCopy() = runBlocking<Unit> {
        val disk = Device(Cloud())
        val store = open(disk, connect = false)
        store.budget.saveTransaction(Transaction("t1", "chk", LocalDate(2026, 10, 1), -1234, "Corner Bakery"))
        store.setPassphrase(passphrase)
        store.flush()
        val saved = disk.local.getValue(BudgetStore.LEDGER_FILE)

        disk.key = null // e.g. the Keychain item was removed
        val reopened = open(disk, connect = false)
        assertIs<LockState.NeedsPassphrase>(reopened.lock.value)
        reopened.flush()
        assertEquals(saved, disk.local.getValue(BudgetStore.LEDGER_FILE), "nothing is written while locked")
        assertTrue(reopened.unlock(passphrase))
        assertEquals("Corner Bakery", reopened.data.value.transactions["t1"]?.payee)
    }

    @Test
    fun changingThePassphraseLocksOtherDevicesUntilTheyEnterTheNewOne() = runBlocking<Unit> {
        val cloud = Cloud()
        val mac = open(Device(cloud))
        mac.setPassphrase(passphrase)
        val phone = open(Device(cloud))
        phone.syncNow()
        assertTrue(phone.unlock(passphrase))

        mac.setPassphrase("a brand new passphrase")
        phone.syncNow()
        assertIs<SyncStatus.Locked>(phone.syncStatus.value)
        assertFalse(phone.unlock(passphrase))
        assertTrue(phone.unlock("a brand new passphrase"))
    }

    @Test
    fun archivesFromAnOlderPassphraseAskForIt() = runBlocking<Unit> {
        val store = open(Device(Cloud()), connect = false)
        store.budget.saveTransaction(Transaction("t1", "chk", LocalDate(2026, 10, 1), -1234, "Corner Bakery"))
        store.setPassphrase(passphrase)
        val archive = store.archiveCurrent().text
        store.setPassphrase("a brand new passphrase")
        assertFailsWith<VaultLockedException> { store.decodeArchive(archive) }
        assertFailsWith<VaultLockedException> { store.decodeArchive(archive, "wrong passphrase") }
        assertTrue(store.decodeArchive(archive, passphrase).ops.any { it.key == "txn/t1" })
    }

    @Test
    fun fullAccountNumbersAreNeverStored() = runBlocking<Unit> {
        // Bank files carry the full number; only the bank id and last 4 digits are kept.
        val stmt = OfxParser.parse(Samples.QBO_CHECKING).single()
        assertTrue(AccountKeys.isMasked(stmt.accountKey))
        assertFalse(stmt.accountKey.contains(stmt.accountId))
        assertTrue(stmt.accountKey.endsWith("••" + stmt.accountId.takeLast(4)))
        assertEquals("card-••4321", AccountKeys.of(null, "4111111111114321"))
        assertEquals("bank-021000021-••6789", AccountKeys.mask("021000021-123456789"))
        assertEquals("card-••4321", AccountKeys.mask("4111111111114321"))

        // A budget saved by an older version, with the full numbers, is cleaned up when it opens.
        val old = BudgetStore(Device(Cloud()), scope).also { runBlocking { it.start() } }
        old.budget.saveAccount(Account("chk", "Checking", importKeys = listOf("021000021-123456789")))
        old.budget.saveAccount(Account("cc", "Card", importKeys = listOf("4111111111114321")))
        old.flush()
        val disk = Device(Cloud()).apply { local[BudgetStore.LEDGER_FILE] = old.budget.ledger.encode() }
        val reopened = open(disk, connect = false)
        assertEquals(listOf("bank-021000021-••6789"), reopened.data.value.accounts.getValue("chk").importKeys)
        assertEquals(listOf("card-••4321"), reopened.data.value.accounts.getValue("cc").importKeys)
        val saved = disk.local.getValue(BudgetStore.LEDGER_FILE)
        assertFalse(saved.contains("123456789"))
        assertFalse(saved.contains("4111111111114321"))
        // Still decodes as a normal budget (no passphrase set here).
        Ledger.decodeFile(saved)
    }

    @Test
    fun mergedConflictCopiesAreEncryptedToo() = runBlocking<Unit> {
        val cloud = Cloud()
        val mac = open(Device(cloud))
        mac.budget.saveTransaction(Transaction("t1", "chk", LocalDate(2026, 10, 1), -1234, "Corner Bakery"))
        mac.syncNow()
        // The cloud service left a copy of an earlier version next to the file, plus an unrelated file.
        cloud.copies += cloud.main
        cloud.copies += """{"not":"an IMAB budget"}"""
        mac.setPassphrase(passphrase)
        assertTrue(Vault.isEncrypted(cloud.copies[0]))
        assertFalse(cloud.copies[0].contains("Corner Bakery"))
        assertEquals("""{"not":"an IMAB budget"}""", cloud.copies[1], "files that aren't this budget are left alone")
    }
}
