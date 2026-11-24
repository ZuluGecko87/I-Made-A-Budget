package com.z.imab.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import com.z.imab.platform.PickedFile
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.z.imab.data.LockState
import com.z.imab.data.BudgetStore
import com.z.imab.data.SyncStatus
import com.z.imab.platform.Pickers

/** [inNav] screens get a tab; Import is opened from Accounts (or by dropping a file). */
enum class Screen(val label: String, val icon: ImageVector, val inNav: Boolean = true) {
    Budget("Budget", DollarIcon),
    Accounts("Accounts", Icons.Default.AccountBox),
    Goals("Goals", Icons.Default.Star),
    Reports("Reports", Icons.AutoMirrored.Filled.List),
    Scheduled("Scheduled", Icons.Default.DateRange),
    Import("Import", Icons.Default.Refresh, inNav = false),
    Expenses("Work expenses", ReceiptIcon, inNav = false),
    Settings("Settings", Icons.Default.Settings),
}

/** Pseudo account id for the "needs a category" list across all accounts. */
const val UNCATEGORIZED_VIEW = "__uncategorized__"

@Stable
class NavState {
    var screen by mutableStateOf(Screen.Budget)
    var accountId by mutableStateOf<String?>(null)
    var importAccountId by mutableStateOf<String?>(null)

    /** Loan account whose payoff planner is open (inside Accounts). */
    var loanPlannerFor by mutableStateOf<String?>(null)

    /** A bank file dropped onto the window, waiting for the Import screen to pick it up. */
    var pendingImport by mutableStateOf<PickedFile?>(null)

    /** True while a file is being dragged over the window. */
    var dragging by mutableStateOf(false)

    /** Shown briefly when something other than a bank file is dropped. */
    var dropError by mutableStateOf<String?>(null)

    /** Settings should open the passphrase dialog (from the "Set passphrase" banner). */
    var setUpEncryption by mutableStateOf(false)

    fun go(s: Screen) {
        screen = s
        accountId = null
        loanPlannerFor = null
    }

    fun openAccount(id: String) {
        screen = Screen.Accounts
        accountId = id
        loanPlannerFor = null
    }

    fun importInto(accountId: String?) {
        importAccountId = accountId
        screen = Screen.Import
    }

    /** The account a dropped file should go into: the one whose transactions are on screen. */
    val dropAccountId: String? get() = accountId?.takeIf { screen == Screen.Accounts && it != UNCATEGORIZED_VIEW }

    /** Opens the Import screen with a dropped file, aimed at the account currently shown. */
    fun importFile(file: PickedFile) {
        importAccountId = dropAccountId
        pendingImport = file
        screen = Screen.Import
        accountId = null
    }

    /** Returns false when there's nowhere to go back to (the app should close). */
    fun back(): Boolean {
        if (screen == Screen.Accounts && accountId != null) {
            accountId = null
            return true
        }
        if (screen == Screen.Import) {
            importAccountId?.let(::openAccount) ?: go(Screen.Accounts)
            return true
        }
        if (screen == Screen.Expenses) {
            go(Screen.Reports)
            return true
        }
        if (screen != Screen.Budget) {
            go(Screen.Budget)
            return true
        }
        return false
    }
}

@Composable
/**
 * [modifier] goes on the app's root; the Mac app uses it to accept dropped bank files. [showTutorial]
 * shows the tap-through tutorial after the splash until it has been seen once on this device.
 */
fun App(
    store: BudgetStore,
    pickers: Pickers,
    nav: NavState,
    showSplash: Boolean = true,
    modifier: Modifier = Modifier,
    showTutorial: Boolean = showSplash,
) {
    val settings by store.settings.collectAsState()
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = ImabTypography, shapes = ImabShapes) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val loaded by store.loaded.collectAsState()
            // The splash shows for a few seconds (tap to skip) and until the budget has loaded.
            var splash by remember { mutableStateOf(showSplash) }
            LaunchedEffect(Unit) {
                delay(SPLASH_MILLIS)
                splash = false
            }
            Box(modifier.fillMaxSize()) {
                if (loaded) {
                    val lock by store.lock.collectAsState()
                    if ((lock as? LockState.NeedsPassphrase)?.local == true) {
                        UnlockScreen(store)
                    } else {
                        CompositionLocalProvider(
                            LocalCreateCategory provides { name, groupId -> store.budget.addCategory(groupId, name) },
                            LocalPickers provides pickers,
                            LocalStore provides store,
                        ) {
                            MainLayout(store, pickers, nav)
                        }
                    }
                    val lockedOut = (lock as? LockState.NeedsPassphrase)?.local == true
                    AnimatedVisibility(
                        visible = showTutorial && !settings.tutorialSeen && !lockedOut,
                        enter = fadeIn(), exit = fadeOut(tween(300)),
                    ) {
                        TutorialScreen(onDone = { store.setTutorialSeen(true) })
                    }
                    if (nav.dragging) DropOverlay(store, nav)
                    nav.dropError?.let { message ->
                        AlertDialog(
                            onDismissRequest = { nav.dropError = null },
                            title = { Text("Can't import that file") },
                            text = { Text(message) },
                            confirmButton = { TextButton(onClick = { nav.dropError = null }) { Text("OK") } },
                        )
                    }
                }
                AnimatedVisibility(visible = splash || !loaded, enter = fadeIn(), exit = fadeOut(tween(500))) {
                    SplashScreen(onSkip = { splash = false })
                }
            }
        }
    }
}

private const val SPLASH_MILLIS = 3_000L

@Composable
private fun DropOverlay(store: BudgetStore, nav: NavState) {
    val data by store.data.collectAsState()
    val target = nav.dropAccountId?.let { data.accountName(it) }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)).padding(24.dp)
            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (target != null) "Drop to import into $target" else "Drop to import bank file",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
            Text(".qbo, .qfx or .ofx — you'll review everything before it's added", color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun MainLayout(store: BudgetStore, pickers: Pickers, nav: NavState) {
    BoxWithConstraints {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(store, nav)
                VerticalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f)) { Content(store, pickers, nav, wide = true) }
            }
        } else {
            Scaffold(bottomBar = { TabBar(nav) }) { pad ->
                Box(Modifier.padding(pad)) { Content(store, pickers, nav, wide = false) }
            }
        }
    }
}

/** Whether [s] is the tab to highlight; Import and Work expenses belong to Accounts and Reports. */
private fun NavState.isCurrent(s: Screen) =
    screen == s || (s == Screen.Accounts && screen == Screen.Import) || (s == Screen.Reports && screen == Screen.Expenses)

/** The iOS tab bar: a hairline on top, every tab labelled, the current one in the accent color. */
@Composable
private fun TabBar(nav: NavState) {
    val dark = MaterialTheme.colorScheme.background.let { it.red + it.green + it.blue } < 1.5f
    Column(Modifier.fillMaxWidth().background(if (dark) Ios.barBackgroundDark else Ios.barBackground)) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            Screen.entries.filter { it.inNav }.forEach { s ->
                val selected = nav.isCurrent(s)
                val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected, role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null) { nav.go(s) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(s.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                    Text(s.label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, letterSpacing = 0.sp)
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}

/** On a computer or tablet: a sidebar listing the sections, as in iPad and Mac apps. */
@Composable
private fun Sidebar(store: BudgetStore, nav: NavState) {
    Column(Modifier.width(212.dp).fillMaxHeight().background(cellColor).padding(horizontal = 10.dp)) {
        RailHeader(store)
        Screen.entries.filter { it.inNav }.forEach { s ->
            val selected = nav.isCurrent(s)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(MaterialTheme.shapes.medium)
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .selectable(selected, role = Role.Tab) { nav.go(s) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(s.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(s.label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.SemiBold else null)
            }
        }
    }
}

@Composable
private fun RailHeader(store: BudgetStore) {
    val sync by store.syncStatus.collectAsState()
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Nutcracker", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        when (sync) {
            is SyncStatus.Syncing -> Text("Syncing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is SyncStatus.Failed -> Icon(Icons.Default.Warning, "Sync failed", tint = MaterialTheme.colorScheme.error)
            else -> {}
        }
    }
}

@Composable
private fun Content(store: BudgetStore, pickers: Pickers, nav: NavState, wide: Boolean) {
    Column(Modifier.fillMaxSize()) {
        EncryptionBanner(store, onSetUp = { nav.go(Screen.Settings); nav.setUpEncryption = true })
        Box(Modifier.weight(1f)) { Screens(store, pickers, nav, wide) }
    }
}

@Composable
private fun Screens(store: BudgetStore, pickers: Pickers, nav: NavState, wide: Boolean) {
    when (nav.screen) {
        Screen.Budget -> BudgetScreen(store, nav, wide)
        Screen.Accounts -> {
            val id = nav.accountId
            when {
                id == null -> AccountsScreen(store, nav)
                nav.loanPlannerFor == id -> LoanPlannerScreen(store, nav, id, wide)
                else -> RegisterScreen(store, nav, id, wide, pickers.supportsFileDrop)
            }
        }
        Screen.Goals -> GoalsScreen(store)
        Screen.Reports -> ReportsScreen(store, onWorkExpenses = { nav.go(Screen.Expenses) })
        Screen.Expenses -> ExpensesScreen(store, pickers, nav)
        Screen.Scheduled -> ScheduledScreen(store, wide)
        Screen.Import -> ImportScreen(store, pickers, nav)
        Screen.Settings -> SettingsScreen(store, pickers, nav)
    }
}
