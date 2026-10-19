package com.z.imab

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.z.imab.data.BudgetStore
import com.z.imab.ui.App
import com.z.imab.ui.NavState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking

fun main() = application {
    val store = remember { BudgetStore(DesktopStorage(), MainScope()) }
    val pickers = remember { DesktopPickers() }
    val nav = remember { NavState() }
    LaunchedEffect(Unit) { store.start() }

    Window(
        onCloseRequest = {
            // Runs on the UI thread, so the store isn't touched concurrently; IO hops to Dispatchers.IO.
            runBlocking(Dispatchers.IO) { store.flush() }
            exitApplication()
        },
        title = "IMAB — I Made A Budget",
        state = rememberWindowState(width = 1200.dp, height = 820.dp),
    ) {
        App(store, pickers, nav)
    }
}
