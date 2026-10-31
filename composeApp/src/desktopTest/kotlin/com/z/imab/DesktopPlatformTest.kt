package com.z.imab

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlatformTest {
    private val home = File(System.getProperty("user.home"))

    @Test
    fun dataStaysWhereEachSystemExpectsIt() {
        val expected = when {
            isMac -> File(home, "Library/Application Support/IMAB")
            isWindows -> File(System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path, "IMAB")
            else -> File(home, ".imab")
        }
        assertEquals(expected.absolutePath, appDataDir().absolutePath)
    }

    @Test
    fun cloudFoldersExistAndPutOneDriveFirst() {
        val clouds = cloudFolders()
        assertTrue(clouds.all { it.isDirectory })
        clouds.indexOfFirst { it.name.startsWith("OneDrive") }.takeIf { it >= 0 }?.let { assertEquals(0, it) }
    }
}
