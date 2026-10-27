package com.z.imab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.Transaction
import com.z.imab.ui.LightColors
import com.z.imab.ui.PayeesCard
import kotlinx.datetime.LocalDate
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PayeesCardTest {
    @Test
    fun topFiveThenPagesAndSearch() = runComposeUiTest {
        var now = 1L
        val b = Budget("dev") { now++ }.apply {
            seedDefaults()
            saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 10, 1))
            // 30 payees; Payee 01 is used most, then 02, ...
            for (i in 1..30) for (k in 0..(30 - i)) {
                saveTransaction(Transaction("t$i-$k", "chk", LocalDate(2026, 10, 2), -100, "Payee %02d".format(i)))
            }
        }
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                Surface { Column(Modifier.verticalScroll(rememberScrollState())) { PayeesCard(b.data, b) } }
            }
        }
        fun count() = onAllNodes(hasText("Payee ", substring = true) and !hasSetTextAction()).fetchSemanticsNodes().size

        assertEquals(5, count())
        onNodeWithText("Payee 01").assertExists()
        onNodeWithText("Payee 06").assertDoesNotExist()

        onNodeWithText("Show all 30 payees").performClick()
        assertEquals(25, count())
        onNodeWithText("1–25 of 30").assertExists()
        onNodeWithContentDescription("Next page").performScrollTo().performClick()
        assertEquals(5, count())
        onNodeWithText("26–30 of 30").assertExists()
        onNodeWithText("10").performScrollTo().performClick()
        assertEquals(10, count())
        onNodeWithText("1–10 of 30").assertExists()
        System.getProperty("imab.screenshots")?.let {
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(File("build/screenshots").apply { mkdirs() }, "settings-payees-paged.png"))
        }

        onNodeWithText("Show top 5").performScrollTo().performClick()
        onNode(hasSetTextAction()).performTextInput("Payee 1")
        // Payee 10-19 match; the top 5 of those show.
        assertEquals(5, count())
        onNodeWithText("Show all 10 matches").assertExists()
    }
}
