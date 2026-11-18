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
package dev.octoshrimpy.quik.feature.blocking.numbers

import android.content.Context
import android.net.Uri
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.common.util.extensions.makeToast
import dev.octoshrimpy.quik.interactor.MarkBlocked
import dev.octoshrimpy.quik.interactor.MarkUnblocked
import dev.octoshrimpy.quik.repository.BlockingRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class BlockedNumbersPresenter @Inject constructor(
    private val context: Context,
    private val blockingRepo: BlockingRepository,
    private val conversationRepo: ConversationRepository,
    private val markBlocked: MarkBlocked,
    private val markUnblocked: MarkUnblocked,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences
) : QkPresenter<BlockedNumbersView, BlockedNumbersState>(
        BlockedNumbersState(numbers = blockingRepo.getBlockedNumbers())
) {

    override fun bindIntents(view: BlockedNumbersView) {
        super.bindIntents(view)

        view.unblockAddress()
            .observeOn(Schedulers.io())
            .doOnNext { id ->
                blockingRepo.getBlockedNumber(id)?.address
                    ?.let { address -> conversationRepo.getConversation(listOf(address)) }
                    ?.let { conversation -> markUnblocked.execute(listOf(conversation.id)) }
            }
            .doOnNext(blockingRepo::unblockNumber)
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe()

        view.addAddress()
            .autoDisposable(view.scope())
            .subscribe { view.showAddDialog() }

        view.saveAddress()
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe { address -> blockingRepo.blockNumber(address) }

        // Export: ask for a file to write once we know there's something to export
        view.optionsItemSelected()
            .filter { itemId -> itemId == R.id.exportNumbers }
            .observeOn(Schedulers.io())
            .map { blockingRepo.getBlockedAddresses().isEmpty() }
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe { empty ->
                if (empty) {
                    context.makeToast(R.string.blocked_numbers_export_empty)
                } else {
                    val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    view.selectExportFile("quik-blocked-numbers-$date.txt")
                }
            }

        view.optionsItemSelected()
            .filter { itemId -> itemId == R.id.importNumbers }
            .autoDisposable(view.scope())
            .subscribe { view.selectImportFile() }

        view.exportFileSelected()
            .observeOn(Schedulers.io())
            .map { uri -> exportTo(uri) }
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe { message -> context.makeToast(message) }

        view.importFileSelected()
            .observeOn(Schedulers.io())
            .map { uri -> importFrom(uri) }
            .observeOn(AndroidSchedulers.mainThread())
            .autoDisposable(view.scope())
            .subscribe { message -> context.makeToast(message) }
    }

    /**
     * Writes the blocked numbers to [uri] as plain text, one per line. Returns the message to show
     */
    private fun exportTo(uri: Uri): String = try {
        val addresses = blockingRepo.getBlockedAddresses()
        // "wt" truncates: some document providers keep the old contents with plain "w"
        val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Couldn't open $uri for writing")
        stream.bufferedWriter().use { writer ->
            writer.write("# QUIK blocked numbers, one per line\n")
            addresses.forEach { address -> writer.write(address); writer.write("\n") }
        }
        context.getString(R.string.blocked_numbers_export_done)
    } catch (e: Exception) {
        Timber.w(e, "Couldn't export blocked numbers")
        context.getString(R.string.blocked_numbers_file_error)
    }

    /**
     * Blocks the numbers listed in [uri]: a plain text file (one per line, # comments allowed) or a
     * QUIK backup file with a "blockedNumbers" list. Returns the message to show
     */
    private fun importFrom(uri: Uri): String = try {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IOException("Couldn't open $uri for reading")

        val numbers = parseNumbers(text)
        if (numbers.isEmpty()) {
            context.getString(R.string.blocked_numbers_import_empty)
        } else {
            val existing = blockingRepo.getBlockedAddresses()
            val newNumbers = numbers.filter { number ->
                existing.none { blocked -> phoneNumberUtils.compare(blocked, number) }
            }
            if (newNumbers.isEmpty()) {
                context.getString(R.string.blocked_numbers_import_none_new)
            } else {
                blockingRepo.blockNumber(*newNumbers.toTypedArray())

                // Move any existing conversations with these numbers to blocked, like blocking from the inbox does
                val threadIds = newNumbers.mapNotNull { number -> conversationRepo.getConversation(listOf(number))?.id }
                if (threadIds.isNotEmpty()) {
                    markBlocked.execute(MarkBlocked.Params(threadIds, prefs.blockingManager.get(), null))
                }

                context.resources.getQuantityString(R.plurals.blocked_numbers_import_done, newNumbers.size, newNumbers.size)
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "Couldn't import blocked numbers")
        context.getString(R.string.blocked_numbers_file_error)
    }

    private fun parseNumbers(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val array = JSONObject(trimmed).optJSONArray("blockedNumbers") ?: return emptyList()
            return (0 until array.length())
                    .map { index -> array.getString(index).trim() }
                    .filter { number -> number.isNotEmpty() }
                    .distinct()
        }
        return trimmed.lines()
                .map { line -> line.trim() }
                .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
                .distinct()
    }

}
