package com.z.imab

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.Transaction
import com.z.imab.ui.LightColors
import com.z.imab.ui.LocalCreateCategory
import com.z.imab.ui.TransactionDialog
import kotlinx.datetime.LocalDate
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** The payee pop-up and creating a category from the category picker, in the transaction form. */
@OptIn(ExperimentalTestApi::class)
class PayeeAndNewCategoryTest {
    private fun shot(provider: SemanticsNodeInteractionsProvider, name: String) {
        if (System.getProperty("imab.screenshots") == null) return
        val out = File("build/screenshots").apply { mkdirs() }
        ImageIO.write(provider.onAllNodes(isDialog()).onLast().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    @Test
    fun pickOrAddPayeeAndCreateCategoryOnTheSpot() = runComposeUiTest {
        var now = 1L
        val b = Budget("dev") { now++ }.apply {
            seedDefaults()
            saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 10, 1))
            saveTransaction(Transaction("k", "chk", LocalDate(2026, 10, 2), -1000, "Kroger", categoryId = "seed-groceries"))
        }
        var saved: Transaction? = null
        // Like the app, the form sees the budget update when a category is added.
        var data by mutableStateOf(b.data)
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                CompositionLocalProvider(LocalCreateCategory provides { name, groupId -> b.addCategory(groupId, name).also { data = b.data } }) {
                    TransactionDialog(
                        data, Transaction("new", "chk", LocalDate(2026, 10, 5), -2500), "chk", { "x" },
                        onSave = { saved = it }, onDelete = null, onDismiss = {},
                    )
                }
            }
        }
        // Payee: opens as a pop-up; an existing payee is suggested, a new one can be added.
        onNodeWithText("Choose or add a payee").performClick()
        waitForIdle()
        onNodeWithText("Kroger").assertExists()
        onAllNodes(hasSetTextAction()).onLast().performTextInput("Corner Bakery")
        waitForIdle()
        shot(this, "dialog-payee-popup")
        onNodeWithText("+ Add \"Corner Bakery\" as a new payee").performClick()
        waitForIdle()
        onNodeWithText("Corner Bakery").assertExists()

        // Category: "+ New category" in the picker makes one and selects it.
        onNodeWithText("Category or transfer").performClick()
        waitForIdle()
        onAllNodesWithText("+ New category", substring = true).onLast().performClick()
        waitForIdle()
        onAllNodes(hasSetTextAction()).onLast().performTextReplacement("Bakery")
        shot(this, "dialog-new-category")
        onNodeWithText("Add").performClick()
        waitForIdle()
        val bakery = b.data.categories.values.single { it.name == "Bakery" }
        onNodeWithText("Bakery").assertExists()

        onNodeWithText("Save").performClick()
        assertEquals("Corner Bakery", saved?.payee)
        assertEquals(bakery.id, saved?.categoryId)
    }
}
