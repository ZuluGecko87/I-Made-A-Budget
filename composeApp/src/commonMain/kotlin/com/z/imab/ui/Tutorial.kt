package com.z.imab.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.z.imab.resources.Res
import com.z.imab.resources.nutcracker
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** One card of the first-launch tutorial. [icon] null shows the nutcracker picture. */
private class TutorialPage(val title: String, val body: String, val icon: ImageVector? = null)

private val pages = listOf(
    TutorialPage(
        "Welcome to Nutcracker",
        "An envelope budget: you decide what each dollar is for before you spend it. " +
            "Here's how it works in five quick steps.",
    ),
    TutorialPage(
        "Add your accounts",
        "On the Accounts tab, tap + and enter each account's balance today: checking, savings, " +
            "credit cards, cash. Import the QBO, QFX or OFX file your bank lets you download, or add " +
            "transactions by hand.",
        Icons.Default.AccountBox,
    ),
    TutorialPage(
        "Assign your money",
        "Money in your accounts appears as Ready to Assign at the top of the Budget tab. Tap a " +
            "category to give it some, and keep going until Ready to Assign is zero.",
        DollarIcon,
    ),
    TutorialPage(
        "Record what you spend",
        "Tap + to add a transaction, or Scan receipt to photograph one and have it filled in for you. " +
            "Each category's Available shows what's left to spend.",
        ReceiptIcon,
    ),
    TutorialPage(
        "Save toward goals",
        "Set a goal on any category: an amount each month, or a total by a date. The Goals tab shows " +
            "what each one still needs, with a button to assign just that.",
        Icons.Default.Star,
    ),
    TutorialPage(
        "Sync and stay private",
        "Your budget stays on your devices. To use it on more than one, open Settings → Sync and keep " +
            "the budget file in iCloud Drive, Google Drive, OneDrive or Dropbox. Add a passphrase to " +
            "encrypt it.",
        Icons.Default.Lock,
    ),
)

/**
 * A few cards that explain the app, shown once after the first splash screen (Settings → Help shows
 * them again). Swipe or tap Next; Skip or the last card's button calls [onDone].
 */
@Composable
fun TutorialScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = onDone) { Text("Skip") }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                    PageContent(pages[i])
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                pages.indices.forEach { i ->
                    val color by animateColorAsState(
                        if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    )
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pager.currentPage > 0) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
                }
                Button(
                    onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.widthIn(min = 200.dp),
                ) { Text(if (last) "Start budgeting" else "Next") }
            }
        }
    }
}

@Composable
private fun PageContent(page: TutorialPage) {
    Column(
        Modifier.widthIn(max = 460.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (page.icon == null) {
            Image(
                painterResource(Res.drawable.nutcracker),
                contentDescription = null,
                modifier = Modifier.size(180.dp).shadow(12.dp, RoundedCornerShape(40.dp)).clip(RoundedCornerShape(40.dp)),
            )
        } else {
            Box(
                Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(page.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
