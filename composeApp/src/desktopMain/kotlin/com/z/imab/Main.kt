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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking

/** Window and Dock icon (resources/icon.png, made from icons/Nutcracker-art.png). */
private val appIcon by lazy {
    val bytes = object {}.javaClass.getResourceAsStream("/icon.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

fun main() = application {
    val store = remember { BudgetStore(DesktopStorage(), MainScope()) }
    val pickers = remember { DesktopPickers() }
    val nav = remember { NavState() }
    LaunchedEffect(Unit) { store.start() }
    // Archives are saved next to the sync file, so the restore picker opens there.
    LaunchedEffect(Unit) { store.settings.collect { s -> pickers.lastSyncFolder = s.syncId?.let { java.io.File(it).parent } } }

    Window(
        onCloseRequest = {
            // Runs on the UI thread, so the store isn't touched concurrently; IO hops to Dispatchers.IO.
            runBlocking(Dispatchers.IO) { store.flush() }
            exitApplication()
        },
        title = "Nutcracker",
        icon = appIcon,
        state = rememberWindowState(width = 1200.dp, height = 820.dp),
    ) {
        LaunchedEffect(Unit) {
            // Coming back to the window checks for changes made on other devices.
            window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent) = store.onResume()
                override fun windowLostFocus(e: java.awt.event.WindowEvent) {}
            })
        }
        App(store, pickers, nav, modifier = rememberFileDrop(nav))
    }
}
