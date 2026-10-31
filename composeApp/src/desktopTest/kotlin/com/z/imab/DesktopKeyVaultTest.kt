package com.z.imab

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real Keychain (Mac) / DPAPI (Windows), under a throwaway item name. */
class DesktopKeyVaultTest {
    @Test
    fun savesLoadsAndDeletesInSecureStorage() {
        if (!isMac && !isWindows) return
        val vault = DesktopKeyVault(createTempDirectory("imab-key").toFile(), service = "IMAB test key ${System.nanoTime()}")
        try {
            val secret = """{"salt":"abc","iterations":600000,"key":"xyz+/="}"""
            assertTrue(vault.save(secret))
            assertEquals(secret, vault.load())
            assertTrue(vault.save("$secret!"), "replacing it works too")
            assertEquals("$secret!", vault.load())
        } finally {
            vault.delete()
        }
        assertNull(vault.load())
    }
}
