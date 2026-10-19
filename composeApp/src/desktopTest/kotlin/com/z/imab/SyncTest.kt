package com.z.imab

import com.z.imab.data.BudgetStore
import com.z.imab.data.SyncStatus
import com.z.imab.model.Account
import com.z.imab.model.AccountType
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Two devices syncing through one shared "cloud" file. */
class SyncTest {
    private class Cloud {
        var main = ""
            set(value) { field = value; version++ }
        val copies = mutableListOf<String>()
        var version = 0
        var writes = 0
    }

    private class DeviceStorage(val cloud: Cloud, override val defaultDeviceName: String) : Storage {
        val local = HashMap<String, String>()
        override suspend fun readLocal(name: String) = local[name]
        override suspend fun writeLocal(name: String, text: String) { local[name] = text }
        override suspend fun readSync(location: SyncLocation) = listOf(cloud.main) + cloud.copies
        override suspend fun writeSync(location: SyncLocation, text: String) { cloud.main = text; cloud.writes++ }
        override suspend fun syncStamp(location: SyncLocation) = "v${cloud.version}"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val loc = SyncLocation("cloud", "cloud")

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun device(cloud: Cloud, name: String) = BudgetStore(DeviceStorage(cloud, name), scope).also {
        runBlocking { it.start() }
        it.connectSync(loc)
    }

    @Test
    fun twoDevicesConverge() = runBlocking<Unit> {
        val cloud = Cloud()
        val mac = device(cloud, "mac")
        mac.budget.saveAccount(Account("chk", "Checking"))
        mac.budget.saveTransaction(Transaction("t1", "chk", LocalDate(2026, 10, 1), -500, "Coffee"))
        mac.syncNow()

        val phone = device(cloud, "phone")
        phone.syncNow()
        assertEquals("Coffee", phone.data.value.transactions["t1"]?.payee)
        // Default categories from both devices collapse into one set.
        assertEquals(mac.data.value.categories.keys, phone.data.value.categories.keys)

        phone.budget.saveTransaction(Transaction("t2", "chk", LocalDate(2026, 10, 2), -900, "Lunch"))
        phone.syncNow()
        mac.syncNow()
        assertEquals(mac.data.value, phone.data.value)
    }

    @Test
    fun lostOverwriteIsRepairedOnNextSync() = runBlocking<Unit> {
        val cloud = Cloud()
        val mac = device(cloud, "mac")
        val phone = device(cloud, "phone")
        mac.syncNow(); phone.syncNow()

        mac.budget.saveAccount(Account("a", "From Mac"))
        phone.budget.saveAccount(Account("b", "From phone"))
        // Both upload at nearly the same time and the cloud keeps only the Mac's version.
        mac.syncNow()
        val macUpload = cloud.main
        phone.syncNow()
        cloud.main = macUpload // the mac's version wins the race...
        phone.syncNow() // ...and the phone puts its account back.
        mac.syncNow()
        assertEquals(setOf("a", "b"), mac.data.value.accounts.keys)
        assertEquals(setOf("a", "b"), phone.data.value.accounts.keys)
    }

    @Test
    fun conflictCopiesAreMergedIn() = runBlocking<Unit> {
        val cloud = Cloud()
        val other = device(Cloud(), "other")
        other.budget.saveAccount(Account("c", "Only in conflict copy"))
        cloud.copies += other.budget.ledger.encode()

        val mac = device(cloud, "mac")
        mac.syncNow()
        assertTrue("c" in mac.data.value.accounts)
        assertTrue("c" in cloud.main)
    }

    @Test
    fun fileWithLeftoversFromAnUntruncatedWriteStillSyncsAndIsCleaned() = runBlocking<Unit> {
        val cloud = Cloud()
        val phone = device(cloud, "phone")
        phone.budget.saveAccount(Account("chk", "Checking {main}", AccountType.CHECKING))
        phone.syncNow()
        val longer = cloud.main
        // The phone writes a shorter version, and the cloud app doesn't cut off the old end.
        phone.budget.saveAccount(Account("chk", "Checking", AccountType.CHECKING))
        phone.budget.deleteCategory("seed-gifts")
        phone.syncNow()
        val shorter = cloud.main
        assertTrue(shorter.length < longer.length)
        cloud.main = shorter + longer.substring(shorter.length)

        val mac = device(cloud, "mac")
        mac.syncNow()
        assertIs<SyncStatus.Synced>(mac.syncStatus.value)
        assertEquals("Checking", mac.data.value.accounts.getValue("chk").name)
        assertTrue("seed-gifts" !in mac.data.value.categories)
        // The Mac wrote the file back without the leftovers.
        assertEquals(cloud.main.trim(), com.z.imab.data.Ledger.firstJsonObject(cloud.main))
        assertTrue(com.z.imab.data.Ledger.decode(cloud.main).isNotEmpty())
    }

    @Test
    fun newVersionFromAnotherDeviceIsPickedUpWithoutWaitingForTheTimer() = runBlocking<Unit> {
        val cloud = Cloud()
        val mac = device(cloud, "mac")
        val phone = device(cloud, "phone")
        mac.syncNow(); phone.syncNow()

        phone.budget.saveAccount(Account("new", "From phone"))
        phone.syncNow()
        // The Mac's 5-minute timer isn't due, but the file changed, so the next check syncs.
        mac.tick()
        assertTrue("new" in mac.data.value.accounts)
    }

    @Test
    fun checksDoNothingWhenTheFileHasNotChanged() = runBlocking<Unit> {
        val cloud = Cloud()
        val mac = device(cloud, "mac")
        mac.syncNow()
        val writes = cloud.writes
        repeat(5) { mac.tick() }
        assertEquals(writes, cloud.writes)
        assertIs<SyncStatus.Synced>(mac.syncStatus.value)
    }

    @Test
    fun openingTheAppSavesItsVersionEvenIfNothingChanged() = runBlocking<Unit> {
        val cloud = Cloud()
        val mac = device(cloud, "mac")
        mac.syncNow()
        val writes = cloud.writes
        mac.syncNow(forceWrite = true)
        assertEquals(writes + 1, cloud.writes)
    }

    @Test
    fun neverOverwritesAFileThatIsNotIMAB() = runBlocking<Unit> {
        val cloud = Cloud().apply { main = "{\"ops\": [], \"hello\": \"my spreadsheet\"}" }
        val mac = device(cloud, "mac")
        mac.syncNow()
        assertIs<SyncStatus.Failed>(mac.syncStatus.value)
        assertEquals("{\"ops\": [], \"hello\": \"my spreadsheet\"}", cloud.main)
    }
}
