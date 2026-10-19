package com.z.imab

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
