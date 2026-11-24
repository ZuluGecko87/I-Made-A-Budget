package com.z.imab

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.ui.LightColors
import com.z.imab.ui.TutorialScreen
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** The first-launch tutorial: tap through every card, or skip it. */
@OptIn(ExperimentalTestApi::class)
class TutorialTest {
    private fun ComposeUiTest.show(onDone: () -> Unit) = setContent {
        MaterialTheme(colorScheme = LightColors) { TutorialScreen(onDone) }
    }

    @Test
    fun tapsThroughEveryCard() = runComposeUiTest {
        var done = 0
        show { done++ }
        val titles = listOf(
            "Welcome to Nutcracker", "Add your accounts", "Assign your money",
            "Record what you spend", "Save toward goals", "Sync and stay private",
        )
        titles.forEachIndexed { i, title ->
            onNodeWithText(title).assertExists()
            if (System.getProperty("imab.screenshots") != null) {
                val out = File("build/screenshots/tutorial-${i + 1}.png").apply { parentFile.mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
            }
            if (i < titles.lastIndex) {
                onNodeWithText("Next").performClick()
                waitForIdle()
            }
        }
        onNodeWithText("Skip").assertDoesNotExist()
        assertEquals(0, done)
        onNodeWithText("Start budgeting").performClick()
        assertEquals(1, done)
    }

    @Test
    fun skipEndsIt() = runComposeUiTest {
        var done = 0
        show { done++ }
        onNodeWithText("Skip").performClick()
        assertEquals(1, done)
    }
}
