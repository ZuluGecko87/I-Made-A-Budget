package com.z.imab

import com.z.imab.ui.NavState
import com.z.imab.ui.Screen
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDropTest {
    private val dir = createTempDirectory("imab-drop").toFile()

    private fun file(name: String, text: String = "<OFX></OFX>") = File(dir, name).apply { writeText(text) }

    @Test
    fun acceptsOneBankFileAmongOthers() {
        val picked = bankFileFrom(listOf(file("notes.txt"), file("Chase_Oct.QBO", Samples.QBO_CHECKING))).getOrThrow()
        assertEquals("Chase_Oct.QBO", picked.name)
        assertTrue(picked.text.contains("<STMTTRN>"))
    }

    @Test
    fun explainsWhatIsWrong() {
        assertTrue(bankFileFrom(listOf(file("statement.pdf"))).exceptionOrNull()!!.message!!.contains("isn't a bank file"))
        assertTrue(bankFileFrom(listOf(file("a.qbo"), file("b.ofx"))).exceptionOrNull()!!.message!!.contains("one bank file"))
    }

    /** What macOS hands over when files are dragged from Finder. */
    private class FileList(val files: List<File>) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any = files
    }

    @Test
    fun droppedFileOpensImportForTheAccountOnScreen() {
        val nav = NavState().apply { openAccount("chk"); dragging = true }
        assertTrue(dropFiles(FileList(listOf(file("Chase_Oct.qbo", Samples.QBO_CHECKING))), nav))
        assertEquals(Screen.Import, nav.screen)
        assertEquals("chk", nav.importAccountId)
        assertTrue(nav.pendingImport!!.text.contains("<STMTTRN>"))
        assertFalse(nav.dragging)
        assertNull(nav.dropError)
    }

    @Test
    fun droppingSomethingElseExplains() {
        val nav = NavState()
        assertTrue(dropFiles(FileList(listOf(file("statement.pdf"))), nav))
        assertNull(nav.pendingImport)
        assertTrue(nav.dropError!!.contains("isn't a bank file"))
        assertFalse(dropFiles(StringSelection("some text"), nav))
    }
}
