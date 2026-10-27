package com.z.imab

import com.z.imab.platform.PickedFile
import com.z.imab.ui.NavState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

private val BANK_EXTENSIONS = setOf("qbo", "qfx", "ofx")

/** Picks the bank file out of what was dropped, or explains why there isn't one. */
internal fun bankFileFrom(files: List<File>): Result<PickedFile> {
    val bank = files.filter { it.isFile && it.extension.lowercase() in BANK_EXTENSIONS }
    return when {
        bank.isEmpty() -> Result.failure(
            IllegalArgumentException(
                if (files.size == 1) "\"${files[0].name}\" isn't a bank file. Download a .qbo, .qfx or .ofx file from your bank."
                else "None of those are bank files. Download a .qbo, .qfx or .ofx file from your bank."
            )
        )
        bank.size > 1 -> Result.failure(IllegalArgumentException("Drop one bank file at a time."))
        else -> runCatching { PickedFile(bank[0].name, bank[0].readText()) }
    }
}

/** The files in a drag, or null when it isn't carrying files (text, a browser link…). */
internal fun draggedFiles(t: Transferable): List<File>? =
    if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) null
    else runCatching { (t.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>() }.getOrNull()

/**
 * Handles files dropped on the window: a bank file opens the Import screen with its transactions,
 * aimed at the account whose transactions are showing; anything else explains what's wrong.
 * Returns whether the drop was accepted.
 */
internal fun dropFiles(t: Transferable, nav: NavState): Boolean {
    nav.dragging = false
    val files = draggedFiles(t) ?: return false
    bankFileFrom(files)
        .onSuccess { nav.importFile(it) }
        .onFailure { nav.dropError = it.message ?: "Couldn't read that file." }
    return true
}

/**
 * Lets bank files be dragged onto the window. It has to be a Compose drop target: Compose puts its
 * own AWT drop target on the canvas inside the window, so one set on the window never sees a drop.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun rememberFileDrop(nav: NavState): Modifier {
    val target = remember(nav) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { nav.dragging = true }
            override fun onExited(event: DragAndDropEvent) { nav.dragging = false }
            override fun onEnded(event: DragAndDropEvent) { nav.dragging = false }
            override fun onDrop(event: DragAndDropEvent): Boolean = dropFiles(event.awtTransferable, nav)
        }
    }
    return Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target,
    )
}
