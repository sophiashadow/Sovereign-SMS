package com.sovereignops.sms.ui

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sovereignops.sms.core.crm.ConversationNumbers
import com.sovereignops.sms.core.crm.Relink
import com.sovereignops.sms.vault.CardRow
import com.sovereignops.sms.vault.Vault
import com.sovereignops.sms.vault.VaultCache
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.extensions.conversationsDB

/**
 * Cards restored from a backup whose conversation isn't on this phone (negative ids). Opening
 * the screen first retries the automatic link by number, because the conversation may have
 * appeared since the restore. What is left can be linked by hand or deleted. Nothing is deleted
 * without a confirmation, and a conversation that already has a card is never overwritten.
 */
object SovereignUnlinked {
    fun show(a: Activity, relinkedBefore: Int = 0) = ensureBackgroundThread {
        val dao = Vault.dao(a)
        val conversations = runCatching { a.conversationsDB.getNonArchived() + a.conversationsDB.getAllArchived() }.getOrDefault(emptyList())
        val numbered = conversations.map { ConversationNumbers(it.threadId, it.phoneNumber.split(',').map(String::trim).filter(String::isNotEmpty)) }
        val rows = dao.cards()
        val unlinked = rows.filter { it.threadId < 0 }
        val moves = Relink.plan(unlinked.map { it.threadId to numbersOf(it) }, rows.filter { it.threadId > 0 }.map { it.threadId }.toSet(), numbered)
        if (moves.isNotEmpty()) {
            move(a, moves.map { it.from to it.to })
        }
        val left = dao.cards().filter { it.threadId < 0 }
        val withoutCard = conversations.filter { c -> dao.card(c.threadId) == null }
        val relinked = relinkedBefore + moves.size
        a.runOnUiThread { render(a, left, withoutCard.map { it.threadId to it.title }, relinked) }
    }

    private fun numbersOf(r: CardRow) = r.addresses.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)

    private fun move(a: Activity, pairs: List<Pair<Long, Long>>) {
        val db = Vault.database(a)
        val dao = db.dao()
        db.runInTransaction {
            pairs.forEach { (from, to) ->
                val row = dao.card(from) ?: return@forEach
                if (dao.card(to) == null) {
                    dao.putCard(row.copy(threadId = to, addresses = null))
                    dao.deleteCard(from)
                }
            }
        }
        VaultCache.load(a)
    }

    private fun render(a: Activity, left: List<CardRow>, free: List<Pair<Long, String>>, relinked: Int) {
        val b = MaterialAlertDialogBuilder(a).setTitle(R.string.sv_unlinked).setPositiveButton(android.R.string.ok, null)
        val head = if (relinked > 0) a.getString(R.string.sv_unlinked_relinked, relinked) + "\n\n" else ""
        if (left.isEmpty()) {
            b.setMessage(head + a.getString(R.string.sv_unlinked_empty)).show()
            return
        }
        val labels = left.map { r ->
            a.getString(R.string.sv_unlinked_row, r.alias ?: a.getString(R.string.sv_unlinked_unnamed), r.stage.lowercase(), numbersOf(r).joinToString(", "))
        }.toTypedArray()
        b.setMessage((head + a.getString(R.string.sv_unlinked_hint)).trim())
            .setItems(labels) { _, i -> actions(a, left[i], free, relinked) }
            .show()
    }

    private fun actions(a: Activity, row: CardRow, free: List<Pair<Long, String>>, relinked: Int) {
        MaterialAlertDialogBuilder(a)
            .setItems(arrayOf(a.getString(R.string.sv_unlinked_link), a.getString(R.string.sv_unlinked_delete))) { _, which ->
                if (which == 0) pick(a, row, free, relinked) else confirmDelete(a, row, relinked)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> show(a, relinked) }
            .show()
    }

    private fun pick(a: Activity, row: CardRow, free: List<Pair<Long, String>>, relinked: Int) {
        if (free.isEmpty()) { a.toast(R.string.sv_unlinked_no_conv); show(a, relinked); return }
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.sv_unlinked_pick)
            .setItems(free.map { it.second }.toTypedArray()) { _, i ->
                ensureBackgroundThread {
                    move(a, listOf(row.threadId to free[i].first))
                    a.runOnUiThread { a.toast(R.string.sv_unlinked_linked) }
                    show(a, relinked)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> show(a, relinked) }
            .show()
    }

    private fun confirmDelete(a: Activity, row: CardRow, relinked: Int) {
        MaterialAlertDialogBuilder(a)
            .setMessage(R.string.sv_unlinked_delete_confirm)
            .setPositiveButton(R.string.sv_unlinked_delete) { _, _ ->
                ensureBackgroundThread {
                    Vault.dao(a).deleteCard(row.threadId)
                    VaultCache.load(a)
                    show(a, relinked)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> show(a, relinked) }
            .show()
    }
}
