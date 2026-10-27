package com.z.imab.ui

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
import androidx.compose.material.icons.filled.Home
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
import com.z.imab.data.BudgetStore
import com.z.imab.data.SyncStatus
import com.z.imab.platform.Pickers

/** [inNav] screens get a tab; Import is opened from Accounts (or by dropping a file). */
enum class Screen(val label: String, val icon: ImageVector, val inNav: Boolean = true) {
    Budget("Budget", Icons.Default.Home),
    Accounts("Accounts", Icons.Default.AccountBox),
    Goals("Goals", Icons.Default.Star),
    Reports("Reports", Icons.AutoMirrored.Filled.List),
    Scheduled("Scheduled", Icons.Default.DateRange),
    Import("Import", Icons.Default.Refresh, inNav = false),
    Settings("Settings", Icons.Default.Settings),
}

/** Pseudo account id for the "needs a category" list across all accounts. */
const val UNCATEGORIZED_VIEW = "__uncategorized__"

@Stable
class NavState {
    var screen by mutableStateOf(Screen.Budget)
    var accountId by mutableStateOf<String?>(null)
    var importAccountId by mutableStateOf<String?>(null)

    /** A bank file dropped onto the window, waiting for the Import screen to pick it up. */
    var pendingImport by mutableStateOf<PickedFile?>(null)

    /** True while a file is being dragged over the window. */
    var dragging by mutableStateOf(false)

    /** Shown briefly when something other than a bank file is dropped. */
    var dropError by mutableStateOf<String?>(null)

    fun go(s: Screen) {
        screen = s
        accountId = null
    }

    fun openAccount(id: String) {
        screen = Screen.Accounts
        accountId = id
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
        if (screen != Screen.Budget) {
            go(Screen.Budget)
            return true
        }
        return false
    }
}

@Composable
/** [modifier] goes on the app's root; the Mac app uses it to accept dropped bank files. */
fun App(store: BudgetStore, pickers: Pickers, nav: NavState, showSplash: Boolean = true, modifier: Modifier = Modifier) {
    val settings by store.settings.collectAsState()
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
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
                    MainLayout(store, pickers, nav)
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
                NavigationRail(header = { RailHeader(store) }) {
                    Screen.entries.filter { it.inNav }.forEach { s ->
                        NavigationRailItem(
                            selected = nav.screen == s || (s == Screen.Accounts && nav.screen == Screen.Import),
                            onClick = { nav.go(s) },
                            icon = { Icon(s.icon, s.label) },
                            label = { Text(s.label) },
                        )
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(1f)) { Content(store, pickers, nav, wide = true) }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Screen.entries.filter { it.inNav }.forEach { s ->
                            NavigationBarItem(
                                selected = nav.screen == s || (s == Screen.Accounts && nav.screen == Screen.Import),
                                onClick = { nav.go(s) },
                                icon = { Icon(s.icon, s.label) },
                                label = { Text(s.label, maxLines = 1) },
                                // Six tabs don't fit labelled on a phone: label only the selected one.
                                alwaysShowLabel = false,
                            )
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad)) { Content(store, pickers, nav, wide = false) }
            }
        }
    }
}

@Composable
private fun RailHeader(store: BudgetStore) {
    val sync by store.syncStatus.collectAsState()
    Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("IMAB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        when (sync) {
            is SyncStatus.Syncing -> Text("syncing…", style = MaterialTheme.typography.labelSmall)
            is SyncStatus.Failed -> Icon(Icons.Default.Warning, "Sync failed", tint = MaterialTheme.colorScheme.error)
            else -> {}
        }
    }
}

@Composable
private fun Content(store: BudgetStore, pickers: Pickers, nav: NavState, wide: Boolean) {
    when (nav.screen) {
        Screen.Budget -> BudgetScreen(store, nav, wide)
        Screen.Accounts -> {
            val id = nav.accountId
            if (id == null) AccountsScreen(store, nav) else RegisterScreen(store, nav, id, wide, pickers.supportsFileDrop)
        }
        Screen.Goals -> GoalsScreen(store)
        Screen.Reports -> ReportsScreen(store)
        Screen.Scheduled -> ScheduledScreen(store, wide)
        Screen.Import -> ImportScreen(store, pickers, nav)
        Screen.Settings -> SettingsScreen(store, pickers)
    }
}
