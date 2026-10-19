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

enum class Screen(val label: String, val icon: ImageVector) {
    Budget("Budget", Icons.Default.Home),
    Accounts("Accounts", Icons.Default.AccountBox),
    Scheduled("Scheduled", Icons.Default.DateRange),
    Import("Import", Icons.Default.Refresh),
    Settings("Settings", Icons.Default.Settings),
}

/** Pseudo account id for the "needs a category" list across all accounts. */
const val UNCATEGORIZED_VIEW = "__uncategorized__"

@Stable
class NavState {
    var screen by mutableStateOf(Screen.Budget)
    var accountId by mutableStateOf<String?>(null)
    var importAccountId by mutableStateOf<String?>(null)

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

    /** Returns false when there's nowhere to go back to (the app should close). */
    fun back(): Boolean {
        if (screen == Screen.Accounts && accountId != null) {
            accountId = null
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
fun App(store: BudgetStore, pickers: Pickers, nav: NavState, showSplash: Boolean = true) {
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
            Box(Modifier.fillMaxSize()) {
                if (loaded) MainLayout(store, pickers, nav)
                AnimatedVisibility(visible = splash || !loaded, enter = fadeIn(), exit = fadeOut(tween(500))) {
                    SplashScreen(onSkip = { splash = false })
                }
            }
        }
    }
}

private const val SPLASH_MILLIS = 3_000L

@Composable
private fun MainLayout(store: BudgetStore, pickers: Pickers, nav: NavState) {
    BoxWithConstraints {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(header = { RailHeader(store) }) {
                    Screen.entries.forEach { s ->
                        NavigationRailItem(
                            selected = nav.screen == s,
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
                        Screen.entries.forEach { s ->
                            NavigationBarItem(
                                selected = nav.screen == s,
                                onClick = { nav.go(s) },
                                icon = { Icon(s.icon, s.label) },
                                label = { Text(s.label, maxLines = 1) },
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
            if (id == null) AccountsScreen(store, nav) else RegisterScreen(store, nav, id, wide)
        }
        Screen.Scheduled -> ScheduledScreen(store, wide)
        Screen.Import -> ImportScreen(store, pickers, nav)
        Screen.Settings -> SettingsScreen(store, pickers)
    }
}
