package com.z.imab.data

import com.z.imab.model.Account
import com.z.imab.model.Assignment
import com.z.imab.model.BudgetData
import com.z.imab.model.Category
import com.z.imab.model.CategoryGroup
import com.z.imab.model.CombinedGoal
import com.z.imab.model.PayeeRule
import com.z.imab.model.ScheduledTransaction
import com.z.imab.model.Transaction
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * One versioned write to one entity. [data] == null is a deletion (tombstone).
 *
 * The ledger keeps only the newest op per [key], so it is a last-writer-wins map: merging two
 * ledgers in any order on any device gives the same result. That's what makes syncing through a
 * plain file on OneDrive / Google Drive / Dropbox safe - if two devices overwrite each other's file,
 * the next sync from each device simply merges its ops back in.
 */
@Serializable
data class Op(
    val key: String,
    val ts: Long,
    val device: String,
    val data: JsonElement? = null,
) {
    fun isNewerThan(other: Op): Boolean =
        ts > other.ts || (ts == other.ts && device > other.device)
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LedgerFile(
    // Always written, so a sync file can be recognized as IMAB's before anything overwrites it.
    @EncodeDefault val format: String = "",
    @EncodeDefault val version: Int = 1,
    val ops: List<Op>,
) {
    companion object {
        const val FORMAT = "imab-ledger"
    }
}

object Kinds {
    const val ACCOUNT = "account"
    const val GROUP = "group"
    const val CATEGORY = "category"
    const val TXN = "txn"
    const val SCHEDULED = "sched"
    const val ASSIGNMENT = "assign"
    const val RULE = "rule"
    const val COMBINED_GOAL = "cgoal"

    fun key(kind: String, id: String) = "$kind/$id"
}

val ImabJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

class Ledger {
    private val ops = HashMap<String, Op>()

    /** Highest timestamp seen; local writes always go after it so they win over what we've seen. */
    var maxTs: Long = 0
        private set

    val size: Int get() = ops.size

    fun all(): Collection<Op> = ops.values

    operator fun get(key: String): Op? = ops[key]

    fun contains(key: String) = ops.containsKey(key)

    /** Returns true if the op changed the ledger. */
    fun merge(op: Op): Boolean {
        val current = ops[op.key]
        if (current != null && !op.isNewerThan(current)) return false
        ops[op.key] = op
        if (op.ts > maxTs) maxTs = op.ts
        return true
    }

    fun nextTs(now: Long): Long {
        val t = maxOf(now, maxTs + 1)
        maxTs = t
        return t
    }

    fun encode(): String =
        ImabJson.encodeToString(LedgerFile.serializer(), LedgerFile(LedgerFile.FORMAT, ops = ops.values.sortedBy { it.key }))

    /** True if [other] holds exactly the same versions as this ledger. */
    fun sameAs(other: List<Op>): Boolean {
        if (other.size != ops.size) return false
        return other.all { o -> ops[o.key]?.let { it.ts == o.ts && it.device == o.device } == true }
    }

    companion object {
        /**
         * Throws if [text] isn't an IMAB ledger file. Anything after the budget itself is ignored:
         * some cloud apps on Android overwrite a file without cutting it to the new length, leaving
         * the tail of the previous version behind.
         */
        fun decode(text: String): List<Op> {
            val file = ImabJson.decodeFromString(LedgerFile.serializer(), firstJsonObject(text))
            require(file.format == LedgerFile.FORMAT) { "Not an IMAB budget file" }
            return file.ops
        }

        /** True if [text] has leftover bytes after the budget, so the file should be rewritten clean. */
        fun hasLeftovers(text: String): Boolean = firstJsonObject(text).length < text.trim().length

        /** The first complete top-level JSON object in [text] (strings and escapes respected). */
        internal fun firstJsonObject(text: String): String {
            val start = text.indexOf('{')
            if (start < 0) return text
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val c = text[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return text.substring(start, i + 1)
                }
            }
            return text
        }
    }
}

/** Mutable working copy of [BudgetData], kept in step with the ledger. */
class DataBuilder {
    val accounts = HashMap<String, Account>()
    val groups = HashMap<String, CategoryGroup>()
    val categories = HashMap<String, Category>()
    val transactions = HashMap<String, Transaction>()
    val scheduled = HashMap<String, ScheduledTransaction>()
    val assignments = HashMap<String, Assignment>()
    val rules = HashMap<String, PayeeRule>()
    val combinedGoals = HashMap<String, CombinedGoal>()

    fun clear() {
        accounts.clear(); groups.clear(); categories.clear()
        transactions.clear(); scheduled.clear(); assignments.clear(); rules.clear(); combinedGoals.clear()
    }

    fun apply(op: Op) {
        val slash = op.key.indexOf('/')
        if (slash < 0) return
        val kind = op.key.substring(0, slash)
        val id = op.key.substring(slash + 1)
        when (kind) {
            Kinds.ACCOUNT -> set(accounts, id, op.data, Account.serializer())
            Kinds.GROUP -> set(groups, id, op.data, CategoryGroup.serializer())
            Kinds.CATEGORY -> set(categories, id, op.data, Category.serializer())
            Kinds.TXN -> set(transactions, id, op.data, Transaction.serializer())
            Kinds.SCHEDULED -> set(scheduled, id, op.data, ScheduledTransaction.serializer())
            Kinds.ASSIGNMENT -> set(assignments, id, op.data, Assignment.serializer())
            Kinds.RULE -> set(rules, id, op.data, PayeeRule.serializer())
            Kinds.COMBINED_GOAL -> set(combinedGoals, id, op.data, CombinedGoal.serializer())
        }
    }

    private fun <T> set(map: HashMap<String, T>, id: String, data: JsonElement?, serializer: KSerializer<T>) {
        if (data == null) {
            map.remove(id)
            return
        }
        // A record written by a newer app version we can't read is skipped rather than crashing.
        runCatching { ImabJson.decodeFromJsonElement(serializer, data) }
            .onSuccess { map[id] = it }
            .onFailure { map.remove(id) }
    }

    fun snapshot() = BudgetData(
        accounts = accounts.toMap(),
        groups = groups.toMap(),
        categories = categories.toMap(),
        transactions = transactions.toMap(),
        scheduled = scheduled.toMap(),
        assignments = assignments.toMap(),
        rules = rules.toMap(),
        combinedGoals = combinedGoals.toMap(),
    )
}
