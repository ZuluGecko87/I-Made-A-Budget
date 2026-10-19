package com.z.imab

import com.z.imab.platform.PickedFile
import com.z.imab.ui.NavState
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
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

/**
 * Lets bank files be dragged onto the window. A file dropped while an account's transactions are
 * showing is imported into that account; anywhere else it opens the Import screen.
 * AWT calls these on the UI thread, so updating Compose state here is safe.
 */
fun installFileDrop(window: Window, nav: NavState) {
    window.dropTarget = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun dragEnter(e: DropTargetDragEvent) {
            if (e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                e.acceptDrag(DnDConstants.ACTION_COPY)
                nav.dragging = true
            } else {
                e.rejectDrag()
            }
        }

        override fun dragExit(e: DropTargetEvent) {
            nav.dragging = false
        }

        override fun drop(e: DropTargetDropEvent) {
            nav.dragging = false
            if (!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                e.rejectDrop()
                return
            }
            e.acceptDrop(DnDConstants.ACTION_COPY)
            val files = (e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
            e.dropComplete(true)
            bankFileFrom(files)
                .onSuccess { nav.importFile(it) }
                .onFailure { nav.dropError = it.message ?: "Couldn't read that file." }
        }
    }, true)
}
