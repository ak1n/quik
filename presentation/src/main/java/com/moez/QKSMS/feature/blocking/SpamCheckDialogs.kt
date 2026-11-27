/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.feature.blocking

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.blocking.SpamBlockerBlockingClient
import dev.octoshrimpy.quik.blocking.SpamBlockerCheck
import dev.octoshrimpy.quik.common.util.extensions.dpToPx
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The dialogs for checking already-received messages with SpamBlocker: one message or one whole
 * conversation from the conversation screen, or the latest message of every conversation from the
 * blocking settings.
 * Nothing is blocked until the user confirms the results.
 */
class SpamCheckDialogs @Inject constructor(
    private val spamCheck: SpamBlockerCheck,
    private val spamBlocker: SpamBlockerBlockingClient
) {

    fun isAvailable(): Boolean = spamCheck.isActive()

    /** Checks the message with [messageId] and offers to block its conversation */
    fun checkMessage(activity: Activity, messageId: Long) =
            checkOne(activity) { spamCheck.candidateForMessage(messageId) }

    /**
     * Checks the recent received messages of each of [threadIds] (the inbox's multi-select) and
     * reviews them like the all-conversations check, one entry per conversation
     */
    fun checkConversations(activity: Activity, threadIds: List<Long>) {
        when (threadIds.size) {
            0 -> return
            1 -> return checkConversation(activity, threadIds.single())
        }
        runCheck(activity, activity.getString(R.string.spam_check_conversations_title),
                R.string.spam_check_collecting_messages,
                { threadIds.flatMap { threadId -> spamCheck.candidatesForConversation(threadId, SELECTED_CONVERSATION_LIMIT) } },
                { progress -> showReview(activity, progress) })
    }

    /** Checks the received messages of the conversation [threadId] and offers to block it */
    fun checkConversation(activity: Activity, threadId: Long) =
            runCheck(activity, activity.getString(R.string.spam_check_conversation_title),
                    R.string.spam_check_collecting_messages,
                    { spamCheck.candidatesForConversation(threadId) },
                    { progress -> showConversationReview(activity, progress) })

    private fun checkOne(activity: Activity, find: () -> SpamBlockerCheck.Candidate?) {
        val progress = progressDialog(activity, activity.getString(R.string.spam_check_checking))
        val disposable = Single.fromCallable { listOfNotNull(find()) }
                .flatMap { candidates ->
                    when (val candidate = candidates.firstOrNull()) {
                        null -> Single.just(listOf())
                        else -> spamCheck.check(candidate).map { listOf(it) }
                    }
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ results ->
                    progress.dismiss()
                    if (activity.isFinishing) return@subscribe
                    when (val result = results.firstOrNull()) {
                        null -> toast(activity, R.string.spam_check_not_received)
                        else -> showMessageResult(activity, result)
                    }
                }, { e ->
                    Timber.w(e)
                    progress.dismiss()
                })
        progress.setOnCancelListener { disposable.dispose() }
        progress.show()
    }

    private fun showMessageResult(activity: Activity, result: SpamBlockerCheck.Result) {
        val answer = result.answer ?: return unavailable(activity)
        val builder = AlertDialog.Builder(activity)
        val obfuscated = obfuscationNote(activity, listOf(result))

        if (!answer.shouldBlock) {
            val message = StringBuilder(activity.getString(R.string.spam_check_allowed_message))
            allowedBy(activity, listOf(result))?.let { message.append("\n\n").append(it) }
            obfuscated?.let { message.append("\n\n").append(it) }
            builder.setTitle(R.string.spam_check_allowed_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.spam_check_show_text) { _, _ -> showTextSent(activity, result.candidate) }
                    .show()
            return
        }

        val message = StringBuilder(activity.getString(R.string.spam_check_blocked_message,
                answer.reason ?: activity.getString(R.string.spam_check_no_reason)))
        obfuscated?.let { message.append("\n\n").append(it) }
        if (!answer.testHonoured) message.append("\n\n").append(activity.getString(R.string.spam_check_counted_as_real))
        builder.setTitle(R.string.spam_check_blocked_title)
                .setMessage(message)
                .setPositiveButton(R.string.spam_check_move_to_blocked) { _, _ ->
                    block(activity, listOf(result), report = false)
                }
                .setNegativeButton(R.string.button_cancel, null)
        if (answer.testHonoured) {
            builder.setNeutralButton(R.string.spam_check_block_and_report) { _, _ ->
                block(activity, listOf(result), report = true)
            }
        }
        builder.show()
    }

    /**
     * Shares the newest received messages from senders not in the contacts, as plain text with the
     * same normalisation the filters see, for writing rules against what actually arrives
     */
    fun shareRecent(activity: Activity) {
        val progress = progressDialog(activity, activity.getString(R.string.spam_check_collecting_messages))
        val disposable = Single.fromCallable { spamCheck.recentFromNonContacts(RECENT_CONVERSATIONS, RECENT_PER_CONVERSATION) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ candidates ->
                    progress.dismiss()
                    if (activity.isFinishing) return@subscribe
                    if (candidates.isEmpty()) return@subscribe toast(activity, R.string.spam_check_not_received)

                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    val text = buildString {
                        val conversations = candidates.distinctBy { it.threadId }.size
                        append(activity.getString(R.string.spam_check_recent_header, conversations)).append("\n")
                        candidates.groupBy { it.threadId }.values.forEach { messages ->
                            val first = messages.first()
                            append("\n## ").append(first.address)
                            if (first.title != first.address) append(" (").append(first.title).append(")")
                            append("\n")
                            messages.forEach { c ->
                                append("- ").append(dateFormat.format(Date(c.date))).append(": ")
                                        .append(c.normalized.text.replace("\n", " ⏎ ")).append("\n")
                            }
                        }
                    }
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.blocking_share_recent_title)))
                }, { e ->
                    Timber.w(e)
                    progress.dismiss()
                })
        progress.setOnCancelListener { disposable.dispose() }
        progress.show()
    }

    /** Warns how long it takes and what SpamBlocker does with the checks, then runs them */
    fun checkExisting(activity: Activity) {
        val padding = 24.dpToPx(activity)
        val includeContacts = CheckBox(activity).apply { setText(R.string.spam_check_include_contacts) }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(TextView(activity).apply { setText(R.string.spam_check_existing_message) })
            addView(includeContacts)
        }

        AlertDialog.Builder(activity)
                .setTitle(R.string.spam_check_existing_title)
                .setView(view)
                .setPositiveButton(R.string.spam_check_start) { _, _ ->
                    runCheck(activity, activity.getString(R.string.spam_check_existing_title), R.string.spam_check_collecting,
                            { spamCheck.candidates(includeContacts.isChecked) },
                            { progress -> showReview(activity, progress) })
                }
                .setNeutralButton(R.string.spam_check_open_spamblocker) { _, _ -> spamBlocker.openSettings() }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }

    /** Checks what [find] returns one at a time with a progress bar, then passes the results to [review] */
    private fun runCheck(
        activity: Activity,
        title: String,
        collecting: Int,
        find: () -> List<SpamBlockerCheck.Candidate>,
        review: (SpamBlockerCheck.Progress) -> Unit
    ) {
        val padding = 24.dpToPx(activity)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val label = TextView(activity).apply { setText(collecting) }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(label)
            addView(bar)
        }

        var latest: SpamBlockerCheck.Progress? = null
        var disposable: Disposable? = null
        val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(view)
                .setCancelable(false)
                // Stopping early still shows what was found so far
                .setNegativeButton(R.string.spam_check_stop) { _, _ ->
                    disposable?.dispose()
                    latest?.let(review)
                }
                .show()

        disposable = Single.fromCallable(find)
                .flatMapObservable(spamCheck::checkAll)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ progress ->
                    latest = progress
                    bar.isIndeterminate = false
                    bar.max = progress.total
                    bar.progress = progress.results.size
                    label.text = activity.getString(R.string.spam_check_progress, progress.results.size, progress.total)
                    if (progress.done && !activity.isFinishing) {
                        dialog.dismiss()
                        review(progress)
                    }
                }, { e ->
                    Timber.w(e)
                    dialog.dismiss()
                })
    }

    private fun showReview(activity: Activity, progress: SpamBlockerCheck.Progress) {
        // One entry per conversation, with the first message SpamBlocker flagged in it
        val flagged = progress.results.filter { it.shouldBlock }.distinctBy { it.candidate.threadId }
        val unanswered = progress.results.count { it.answer == null }
        val checked = progress.results.distinctBy { it.candidate.threadId }.size

        if (flagged.isEmpty()) {
            val message = StringBuilder(activity.resources.getQuantityString(R.plurals.spam_check_none, checked, checked))
            if (unanswered > 0) message.append("\n\n").append(activity.resources.getQuantityString(R.plurals.spam_check_unanswered, unanswered, unanswered))
            AlertDialog.Builder(activity)
                    .setTitle(R.string.spam_check_existing_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            return
        }

        val labels = flagged.map { result ->
            listOfNotNull(result.candidate.title, result.answer?.reason).joinToString(" — ")
        }.toTypedArray()
        val selected = BooleanArray(flagged.size) { true }
        val honoured = flagged.any { it.answer?.testHonoured == true }

        val builder = AlertDialog.Builder(activity)
                .setTitle(activity.resources.getQuantityString(R.plurals.spam_check_review_title, flagged.size, flagged.size, checked))
                .setMultiChoiceItems(labels, selected) { _, which, isChecked -> selected[which] = isChecked }
                .setPositiveButton(R.string.spam_check_move_to_blocked) { _, _ ->
                    block(activity, flagged.filterIndexed { i, _ -> selected[i] }, report = false)
                }
                .setNegativeButton(R.string.button_cancel, null)
        if (honoured) {
            builder.setNeutralButton(R.string.spam_check_block_and_report) { _, _ ->
                block(activity, flagged.filterIndexed { i, _ -> selected[i] }, report = true)
            }
        }
        builder.show()
    }

    /** The result of checking one conversation's messages */
    private fun showConversationReview(activity: Activity, progress: SpamBlockerCheck.Progress) {
        val results = progress.results
        val answers = results.mapNotNull { it.answer }
        when {
            progress.total == 0 -> return toast(activity, R.string.spam_check_not_received)
            results.isNotEmpty() && answers.isEmpty() -> return unavailable(activity)
        }

        val res = activity.resources
        val flagged = results.filter { it.shouldBlock }
        val unanswered = results.size - answers.size
        val message = StringBuilder()
        val builder = AlertDialog.Builder(activity)

        if (flagged.isEmpty()) {
            message.append(res.getQuantityString(R.plurals.spam_check_conversation_none, results.size, results.size))
            allowedBy(activity, results)?.let { message.append("\n\n").append(it) }
        } else {
            builder.setTitle(res.getQuantityString(R.plurals.spam_check_conversation_spam, flagged.size, flagged.size, results.size))
            flagged.take(5).forEach { result ->
                if (message.isNotEmpty()) message.append("\n\n")
                message.append("• ").append(result.answer?.reason ?: activity.getString(R.string.spam_check_no_reason))
                        .append("\n“").append(snippet(result.candidate.body)).append("”")
            }
            if (flagged.size > 5) message.append("\n\n").append(activity.getString(R.string.spam_check_and_more, flagged.size - 5))
            if (flagged.any { it.answer?.testHonoured == false }) {
                message.append("\n\n").append(activity.getString(R.string.spam_check_counted_as_real))
            }
            builder.setPositiveButton(R.string.spam_check_move_to_blocked) { _, _ -> block(activity, flagged, report = false) }
                    .setNegativeButton(R.string.button_cancel, null)
            if (flagged.any { it.answer?.testHonoured == true }) {
                builder.setNeutralButton(R.string.spam_check_block_and_report) { _, _ -> block(activity, flagged, report = true) }
            }
        }
        if (unanswered > 0) message.append("\n\n").append(res.getQuantityString(R.plurals.spam_check_unanswered_messages, unanswered, unanswered))
        obfuscationNote(activity, results)?.let { message.append("\n\n").append(it) }
        if (progress.results.size < progress.total) {
            message.append("\n\n").append(activity.getString(R.string.spam_check_stopped, progress.results.size, progress.total))
        }

        if (flagged.isEmpty()) {
            builder.setTitle(R.string.spam_check_allowed_title).setPositiveButton(android.R.string.ok, null)
            // The newest message, for working out why a rule didn't match
            results.firstOrNull()?.let { newest ->
                builder.setNeutralButton(R.string.spam_check_show_text) { _, _ -> showTextSent(activity, newest.candidate) }
            }
        }
        builder.setMessage(message).show()
    }

    /**
     * The text exactly as SpamBlocker received it, in a monospace face so look-alike glyphs (I/l,
     * O/0) can be told apart, and every character outside plain ASCII named
     */
    private fun showTextSent(activity: Activity, candidate: SpamBlockerCheck.Candidate) {
        val normalized = candidate.normalized
        val unusual = codePoints(candidate.body).filter { cp -> cp > 0x7E }.groupingBy { it }.eachCount()
        val detail = StringBuilder()
        if (normalized.changed) detail.append(activity.getString(R.string.spam_check_text_fixed, normalized.total)).append("\n\n")
        detail.append(activity.getString(when (unusual.isEmpty()) {
            true -> R.string.spam_check_text_none
            false -> R.string.spam_check_text_unusual
        }))
        unusual.forEach { (cp, count) ->
            detail.append("\nU+%04X %s ×%d".format(cp, Character.getName(cp) ?: "?", count))
        }

        val padding = 24.dpToPx(activity)
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(TextView(activity).apply {
                text = normalized.text
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
            addView(TextView(activity).apply { text = detail; setPadding(0, padding / 2, 0, 0) })
        }
        // A plain-ASCII copy survives any messenger or keyboard: U+XXXX in place of every unusual character
        val dump = StringBuilder()
        codePoints(candidate.body).forEach { cp ->
            if (cp > 0x7E) dump.append("<U+%04X>".format(cp)) else dump.appendCodePoint(cp)
        }
        dump.append("\n\n").append(detail)

        AlertDialog.Builder(activity)
                .setTitle(R.string.spam_check_text_title)
                .setView(ScrollView(activity).apply { addView(view) })
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.spam_check_share_text) { _, _ ->
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, dump.toString())
                    activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.spam_check_text_title)))
                }
                .show()
    }

    private fun codePoints(text: String): List<Int> {
        val list = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            list += cp
            i += Character.charCount(cp)
        }
        return list
    }

    /** SpamBlocker's reasons for letting [results] through, if it gave any */
    private fun allowedBy(activity: Activity, results: List<SpamBlockerCheck.Result>): String? = results
            .mapNotNull { it.answer }
            .filter { !it.shouldBlock }
            .mapNotNull { it.reason?.takeIf(String::isNotBlank) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { reasons -> activity.getString(R.string.spam_check_allowed_by, reasons.joinToString("; ")) }

    /** How many of [results] had hidden or look-alike characters undone before they were checked */
    private fun obfuscationNote(activity: Activity, results: List<SpamBlockerCheck.Result>): String? {
        val obfuscated = results.filter { it.candidate.normalized.changed }
        return when {
            obfuscated.isEmpty() -> null
            results.size == 1 -> obfuscated.single().candidate.normalized.total.let { n ->
                activity.resources.getQuantityString(R.plurals.spam_check_obfuscated, n, n)
            }
            else -> activity.resources.getQuantityString(R.plurals.spam_check_obfuscated_messages, obfuscated.size, obfuscated.size)
        }
    }

    private fun snippet(body: String): String = body.replace(Regex("\\s+"), " ").trim().let { text ->
        if (text.length > 80) text.take(80).trimEnd() + "…" else text
    }

    private fun block(activity: Activity, results: List<SpamBlockerCheck.Result>, report: Boolean) {
        if (results.isEmpty()) return
        val conversations = results.distinctBy { it.candidate.threadId }.size
        spamCheck.block(results, report)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    Toast.makeText(activity, activity.resources.getQuantityString(
                            R.plurals.spam_check_moved, conversations, conversations), Toast.LENGTH_SHORT).show()
                }, Timber::w)
    }

    private fun unavailable(activity: Activity) {
        AlertDialog.Builder(activity)
                .setTitle(R.string.spam_check_existing_title)
                .setMessage(R.string.spam_check_unavailable)
                .setPositiveButton(android.R.string.ok, null)
                .show()
    }

    private fun progressDialog(activity: Activity, message: String): AlertDialog {
        val padding = 24.dpToPx(activity)
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, padding)
            addView(ProgressBar(activity))
            addView(TextView(activity).apply { text = message; setPadding(padding, 0, 0, 0) })
        }
        return AlertDialog.Builder(activity).setView(view).setCancelable(true).create()
    }

    private fun toast(activity: Activity, res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()

    companion object {
        /** Received messages checked per conversation when several are selected */
        private const val SELECTED_CONVERSATION_LIMIT = 10
        /** What "share recent messages" covers */
        private const val RECENT_CONVERSATIONS = 60
        private const val RECENT_PER_CONVERSATION = 5
    }

}
