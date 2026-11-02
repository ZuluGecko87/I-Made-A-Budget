package com.z.imab

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.SplitLine
import com.z.imab.model.Transaction
import com.z.imab.ui.LightColors
import com.z.imab.ui.TransactionDialog
import kotlinx.datetime.LocalDate
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Splitting a transaction across categories in the transaction form. */
@OptIn(ExperimentalTestApi::class)
class SplitTransactionUiTest {
    private fun shot(provider: SemanticsNodeInteractionsProvider, name: String) {
        if (System.getProperty("imab.screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        ImageIO.write(provider.onAllNodes(isDialog()).onLast().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun budget() = Budget("dev") { 1L }.apply {
        seedDefaults()
        saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 10, 1))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.pick(field: String, option: String) {
        onNodeWithText(field).performScrollTo().performClick()
        waitForIdle()
        // The category list is long, so it opens as a pop-up with a search box.
        onAllNodes(hasSetTextAction()).onLast().performTextInput(option)
        waitForIdle()
        onAllNodesWithText(option).onLast().performClick()
        waitForIdle()
    }

    private fun SemanticsNodeInteractionsProvider.type(field: String, text: String) {
        onNode(hasSetTextAction() and hasText(field)).performScrollTo().performTextReplacement(text)
    }

    @Test
    fun splitAcrossThreeCategories() = runComposeUiTest {
        val b = budget()
        var saved: Transaction? = null
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                TransactionDialog(
                    b.data, Transaction("new", "chk", LocalDate(2026, 10, 5), -12000, "Big Box", categoryId = "seed-groceries"), "chk", { "x" },
                    onSave = { saved = it }, onDelete = null, onDismiss = {},
                )
            }
        }
        onNodeWithText("Split across categories").performClick()
        waitForIdle()
        // The first line starts with the category and the whole amount.
        onNodeWithText("Groceries").assertExists()
        type("Amount 1", "70")
        pick("Category 2", "Household")
        type("Amount 2", "30")
        onNodeWithText("$20.00 left to split").assertExists()
        onNodeWithText("Save").assertIsNotEnabled()

        onNodeWithText("+ Add another category").performScrollTo().performClick()
        pick("Category 3", "Dining Out")
        onNodeWithText("Fill in the rest").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("✓ The categories add up to $120.00.").assertExists()
        shot(this, "dialog-split")
        onNodeWithText("Save").assertIsEnabled().performClick()

        val t = saved!!
        assertNull(t.categoryId)
        assertEquals(
            listOf(SplitLine("seed-groceries", -7000), SplitLine("seed-household", -3000), SplitLine("seed-dining-out", -2000)),
            t.splits,
        )
        b.saveTransaction(t)
        assertEquals(3, b.data.transactions.getValue("new").splits.size)
    }

    @Test
    fun overTheTotalCantBeSavedAndUnsplitGoesBack() = runComposeUiTest {
        val b = budget()
        var saved: Transaction? = null
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                TransactionDialog(
                    b.data,
                    Transaction(
                        "s", "chk", LocalDate(2026, 10, 5), -5000, "Pharmacy",
                        splits = listOf(SplitLine("seed-medical", -4000), SplitLine("seed-household", -1000)),
                    ),
                    "chk", { "x" }, onSave = { saved = it }, onDelete = null, onDismiss = {},
                )
            }
        }
        // An existing split opens with its lines, amounts shown as typed (positive for an outflow).
        onNodeWithText("Medical").assertExists()
        onNodeWithText("40.00").assertExists()
        type("Amount 2", "25")
        onNodeWithText("Over by $15.00").assertExists()
        onNodeWithText("Save").assertIsNotEnabled()
        shot(this, "dialog-split-over")

        // Calculations work in the lines too.
        type("Amount 2", "5 + 5")
        onNodeWithText("✓ The categories add up to $50.00.").assertExists()

        onNodeWithText("Unsplit").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Category or transfer").assertExists()
        onNodeWithText("Save").performClick()
        assertEquals("seed-medical", saved?.categoryId)
        assertEquals(emptyList(), saved?.splits)
    }
}
