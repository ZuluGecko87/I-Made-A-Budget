package com.z.imab.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.z.imab.data.BudgetStore
import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import com.z.imab.model.ReceiptRef
import com.z.imab.platform.Pickers
import com.z.imab.receipt.ExpenseReport
import com.z.imab.receipt.ReceiptDraft
import com.z.imab.receipt.ReceiptMatcher
import com.z.imab.receipt.ReceiptParser
import com.z.imab.receipt.ReceiptScan
import kotlinx.coroutines.launch

/** The platform's pickers, camera and text recognition, for screens that weren't handed them. */
val LocalPickers = staticCompositionLocalOf<Pickers?> { null }

/** The store, for loading and saving receipt photos from inside dialogs. */
val LocalStore = staticCompositionLocalOf<BudgetStore?> { null }

/** A receipt photo that's been read and turned into a draft transaction, not saved yet. */
class PendingReceipt(val draft: ReceiptDraft, val jpeg: ByteArray, val textRead: Boolean)

/** Gets a receipt photo from the camera or the photo library / a file. Errors come back as messages. */
private suspend fun getPhoto(pickers: Pickers, camera: Boolean): Result<ByteArray?> = runCatching {
    if (camera) pickers.takeReceiptPhoto() else pickers.pickReceiptPhoto()
}

/**
 * "Scan receipt": takes or picks a photo, reads it on the device and opens a new transaction with
 * the merchant, total, date and card filled in, and the photo attached. [fallbackAccountId] is the
 * account used when the card on the receipt isn't one the budget knows.
 */
@Composable
fun ScanReceiptButton(
    store: BudgetStore,
    fallbackAccountId: () -> String?,
    iconOnly: Boolean = false,
    /** Start the new transaction ticked as a work expense (scanning from Work expenses). */
    workExpense: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pickers = LocalPickers.current ?: return
    val data by store.data.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingReceipt?>(null) }

    fun start(camera: Boolean) {
        scope.launch {
            val jpeg = getPhoto(pickers, camera).getOrElse { error = it.message ?: "Couldn't get the photo."; return@launch } ?: return@launch
            reading = true
            val lines = runCatching { pickers.readText(jpeg) }.getOrNull()
            reading = false
            val scan = lines?.let { ReceiptParser.parse(it, today()) } ?: ReceiptScan()
            val current = store.data.value
            val fallback = fallbackAccountId()?.takeIf { id -> current.accounts[id]?.closed == false }
                ?: current.sortedAccounts.firstOrNull { it.inBudget && !it.closed }?.id
                ?: current.sortedAccounts.firstOrNull { !it.closed }?.id
            if (fallback == null) {
                error = "Add an account first, then scan the receipt into it."
                return@launch
            }
            val draft = ReceiptMatcher.draft(current, scan, fallback, store.budget.newId(), today())
            pending = PendingReceipt(draft.copy(transaction = draft.transaction.copy(workExpense = workExpense)), jpeg, lines != null)
        }
    }

    Box(modifier) {
        val onClick = { if (pickers.canTakePhoto) menu = true else start(camera = false) }
        if (iconOnly) {
            IconButton(onClick = onClick) { Icon(ReceiptIcon, "Scan receipt") }
        } else {
            TintedButton(onClick = onClick) {
                Icon(ReceiptIcon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scan receipt")
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Take photo") }, onClick = { menu = false; start(camera = true) })
            DropdownMenuItem(text = { Text("Choose photo") }, onClick = { menu = false; start(camera = false) })
        }
    }
    if (reading) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text("Reading the receipt…")
                }
            },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Can't scan the receipt") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
    pending?.let { p ->
        TransactionDialog(
            data = data,
            initial = p.draft.transaction,
            defaultAccountId = p.draft.transaction.accountId,
            newId = store.budget::newId,
            onSave = { t -> store.saveTransaction(t, cardLast4 = p.draft.scan.cardLast4); pending = null },
            onDelete = null,
            onDismiss = { pending = null },
            scan = p,
        )
    }
}

/** What was read from the receipt, so it can be checked against the photo before saving. */
@Composable
internal fun ScanSummary(scan: PendingReceipt, accountId: String, data: BudgetData) {
    val s = scan.draft.scan
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                !scan.textRead -> Text("Text recognition isn't available on this device. Fill in the details from the photo.", style = MaterialTheme.typography.bodySmall)
                s.isEmpty -> Text("Couldn't read this receipt. Fill in the details from the photo.", style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text("Read from the receipt — check it before saving", style = MaterialTheme.typography.labelLarge)
                    @Composable
                    fun line(label: String, value: String?, missing: String) = Text(
                        "$label: ${value ?: missing}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (value == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    line("Merchant", s.merchant, "not found")
                    line("Total", s.total?.let { Money.format(it) }, "not found, enter the amount")
                    line("Date", s.date?.let(ExpenseReport::formatDate), "not found, today is used")
                    val card = listOfNotNull(s.cardBrand, s.cardLast4?.let { "••$it" }).joinToString(" ").ifEmpty { null }
                    if (card != null) {
                        val known = scan.draft.cardAccount
                        Text(
                            when {
                                known != null && known.id == accountId -> "Card: $card → ${known.name}"
                                s.cardLast4 != null -> "Card: $card. Pick the account it pays from; it'll be remembered for the next receipt."
                                else -> "Card: $card"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** A receipt photo, small; tap to see it full size. */
@Composable
fun ReceiptThumbnail(jpeg: ByteArray, modifier: Modifier = Modifier) {
    val image = remember(jpeg) { decodeImage(jpeg) }
    var full by remember { mutableStateOf(false) }
    if (image == null) {
        Text("The photo can't be shown.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Image(
        image, "Receipt photo (tap to enlarge)",
        modifier.heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp)).clickable { full = true },
        contentScale = ContentScale.Fit,
    )
    if (full) {
        AlertDialog(
            onDismissRequest = { full = false },
            confirmButton = { TextButton(onClick = { full = false }) { Text("Close") } },
            text = {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    Image(image, "Receipt photo", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            },
        )
    }
}

/** The receipt and work-expense part of the transaction form. */
@Stable
internal class ReceiptForm(initialRef: ReceiptRef?, newPhoto: ByteArray?, workExpense: Boolean, submitted: kotlinx.datetime.LocalDate?) {
    /** A photo taken or picked in this form, saved with the transaction. */
    var newPhoto by mutableStateOf(newPhoto)
    /** The photo the transaction already had; null once removed. */
    var kept by mutableStateOf(initialRef)
    var workExpense by mutableStateOf(workExpense)
    var submitted by mutableStateOf(submitted)
}

@Composable
internal fun ReceiptFields(form: ReceiptForm) {
    val store = LocalStore.current
    val pickers = LocalPickers.current
    val scope = rememberCoroutineScope()
    var keptImage by remember { mutableStateOf<ByteArray?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(form.kept) {
        keptImage = store?.receiptImage(form.kept)
        loaded = true
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(form.workExpense, { form.workExpense = it })
        Column(Modifier.weight(1f)) {
            Text("Work expense")
            val sub = form.submitted
            if (form.workExpense && sub != null) {
                Text("Submitted ${ExpenseReport.formatDate(sub)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (form.workExpense) {
                Text("Listed under Reports → Work expenses, to export and send in.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (form.workExpense && form.submitted != null) TextButton(onClick = { form.submitted = null }) { Text("Not submitted") }
    }

    val shown = form.newPhoto ?: keptImage
    when {
        shown != null -> ReceiptThumbnail(shown)
        form.kept != null && loaded -> Text(
            "The receipt photo is on ${form.kept?.device?.ifBlank { null } ?: "another device"}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (pickers != null) {
        fun get(camera: Boolean) {
            scope.launch {
                getPhoto(pickers, camera).onSuccess { it?.let { jpeg -> form.newPhoto = jpeg } }.onFailure { error = it.message ?: "Couldn't get the photo." }
            }
        }
        val has = form.newPhoto != null || form.kept != null
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                TextButton(onClick = { if (pickers.canTakePhoto) menu = true else get(camera = false) }) {
                    Text(if (has) "Replace receipt" else "Attach receipt")
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Take photo") }, onClick = { menu = false; get(camera = true) })
                    DropdownMenuItem(text = { Text("Choose photo") }, onClick = { menu = false; get(camera = false) })
                }
            }
            if (has) {
                TextButton(onClick = { form.newPhoto = null; form.kept = null }) {
                    Text("Remove receipt", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Can't attach the photo") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}
