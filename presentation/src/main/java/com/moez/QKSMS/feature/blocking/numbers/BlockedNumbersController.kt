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
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import com.jakewharton.rxbinding2.view.clicks
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.QkActivityResultContracts
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import dev.octoshrimpy.quik.databinding.BlockedNumbersControllerBinding
import dev.octoshrimpy.quik.databinding.BlockedNumbersAddDialogBinding
import javax.inject.Inject

class BlockedNumbersController : QkController<BlockedNumbersControllerBinding, BlockedNumbersView, BlockedNumbersState, BlockedNumbersPresenter>(),
    BlockedNumbersView {

    @Inject override lateinit var presenter: BlockedNumbersPresenter
    @Inject lateinit var colors: Colors
    @Inject lateinit var phoneNumberUtils: PhoneNumberUtils

    private val adapter = BlockedNumbersAdapter()
    private val saveAddressSubject: Subject<String> = PublishSubject.create()
    private val optionsItemSubject: Subject<Int> = PublishSubject.create()
    private val exportFileSubject: Subject<Uri> = PublishSubject.create()
    private val importFileSubject: Subject<Uri> = PublishSubject.create()

    private lateinit var createDocument: ActivityResultLauncher<QkActivityResultContracts.CreateDocumentParams>
    private lateinit var openDocument: ActivityResultLauncher<QkActivityResultContracts.OpenDocumentParams>

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): BlockedNumbersControllerBinding =
        BlockedNumbersControllerBinding.inflate(inflater, container, false)

    override fun onContextAvailable(context: Context) {
        // This controller is pushed onto an activity that's already resumed, so register with the
        // registry directly (the lifecycle-aware overload would throw) and unregister in onDestroy
        val registry = themedActivity!!.activityResultRegistry
        createDocument = registry.register("blocked_numbers_export", QkActivityResultContracts.CreateDocument()) { uri ->
            uri?.takeIf { it != Uri.EMPTY }?.let(exportFileSubject::onNext)
        }
        openDocument = registry.register("blocked_numbers_import", QkActivityResultContracts.OpenDocument()) { uri ->
            uri?.takeIf { it != Uri.EMPTY }?.let(importFileSubject::onNext)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::createDocument.isInitialized) createDocument.unregister()
        if (::openDocument.isInitialized) openDocument.unregister()
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.blocked_numbers_title)
        showBackButton(true)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.blocked_numbers, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        optionsItemSubject.onNext(item.itemId)
        return true
    }

    override fun onViewCreated() {
        super.onViewCreated()
        binding.add.setBackgroundTint(colors.theme().theme)
        binding.add.setTint(colors.theme().textPrimary)
        adapter.emptyView = binding.empty
        binding.numbers.adapter = adapter
    }

    override fun render(state: BlockedNumbersState) {
        adapter.updateData(state.numbers)
    }

    override fun unblockAddress(): Observable<Long> = adapter.unblockAddress
    override fun addAddress(): Observable<*> = binding.add.clicks()
    override fun saveAddress(): Observable<String> = saveAddressSubject
    override fun optionsItemSelected(): Observable<Int> = optionsItemSubject
    override fun exportFileSelected(): Observable<Uri> = exportFileSubject
    override fun importFileSelected(): Observable<Uri> = importFileSubject

    override fun selectExportFile(suggestedName: String) {
        createDocument.launch(QkActivityResultContracts.CreateDocumentParams("text/plain", suggestedName))
    }

    override fun selectImportFile() {
        openDocument.launch(QkActivityResultContracts.OpenDocumentParams(
                mimeTypes = listOf("text/plain", "application/json", "application/octet-stream")))
    }

    override fun showAddDialog() {
        val layout = BlockedNumbersAddDialogBinding.inflate(LayoutInflater.from(activity))
        val textWatcher = BlockedNumberTextWatcher(layout.input, phoneNumberUtils)
        val dialog = AlertDialog.Builder(activity!!)
                .setView(layout.root)
                .setPositiveButton(R.string.blocked_numbers_dialog_block) { _, _ ->
                    saveAddressSubject.onNext(layout.input.text.toString())
                }
                .setNegativeButton(R.string.button_cancel) { _, _ -> }
                .setOnDismissListener { textWatcher.dispose() }
        dialog.show()
    }

}
