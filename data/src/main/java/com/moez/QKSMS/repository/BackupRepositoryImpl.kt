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
package dev.octoshrimpy.quik.repository

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.Telephony
import androidx.core.content.contentValuesOf
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.squareup.moshi.Moshi
import dev.octoshrimpy.quik.common.util.extensions.now
import dev.octoshrimpy.quik.model.BackupFile
import dev.octoshrimpy.quik.manager.AlarmManager
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.MessageContentFilterData
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.model.ScheduledMessage
import dev.octoshrimpy.quik.util.BackupSettings
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.Realm
import okio.buffer
import okio.source
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Timer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.schedule

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val context: Context,
    private val moshi: Moshi,
    private val prefs: Preferences,
    private val sharedPrefs: SharedPreferences,
    private val syncRepo: SyncRepository,
    private val blockingRepo: BlockingRepository,
    private val conversationRepo: ConversationRepository,
    private val contentFilterRepo: MessageContentFilterRepository,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val alarmManager: AlarmManager,
    private val phoneNumberUtils: PhoneNumberUtils
) : BackupRepository {

    /**
     * The backup file. Everything after [messages] lives only in QUIK's own database or
     * preferences, so it would be lost on a reinstall without this. Every field defaults, so
     * older backups still parse.
     */
    data class Backup(
        val messageCount: Int = 0,
        val messages: List<BackupMessage> = listOf(),
        val blockedNumbers: List<String> = listOf(),
        val conversations: List<BackupConversation> = listOf(),
        val contentFilters: List<BackupContentFilter> = listOf(),
        val scheduledMessages: List<BackupScheduledMessage> = listOf(),
        val recipientThemes: List<BackupRecipientTheme> = listOf(),
        val settings: List<BackupSettings.Setting> = listOf()
    )

    /**
     * Simpler version of [Backup] which allows us to read certain fields from the backup without
     * needing to parse the messages
     */
    data class BackupMetadata(
        val messageCount: Int = 0,
        val blockedNumbers: List<String> = listOf(),
        val conversations: List<BackupConversation> = listOf(),
        val contentFilters: List<BackupContentFilter> = listOf(),
        val scheduledMessages: List<BackupScheduledMessage> = listOf(),
        val recipientThemes: List<BackupRecipientTheme> = listOf(),
        val settings: List<BackupSettings.Setting> = listOf()
    )

    /**
     * A conversation's QUIK-only details, keyed by its recipients' addresses rather than the
     * thread id, so it can be matched on another device. The notification fields are null when
     * the conversation uses the global defaults.
     */
    data class BackupConversation(
        val addresses: List<String>,
        val name: String = "",
        val archived: Boolean = false,
        val pinned: Boolean = false,
        val sendAsGroup: Boolean = true,
        val draft: String = "",
        val blocked: Boolean = false,
        val blockingClient: Int? = null,
        val blockReason: String? = null,
        val notifications: Boolean? = null,
        val notificationPreviews: Int? = null,
        val wakeScreen: Boolean? = null,
        val vibration: Boolean? = null,
        val ringtone: String? = null
    ) {
        fun hasDetails(): Boolean = name.isNotBlank() || archived || pinned || !sendAsGroup ||
                draft.isNotBlank() || blocked

        fun settingCount(): Int = listOf(notifications, notificationPreviews, wakeScreen, vibration, ringtone)
                .count { setting -> setting != null }
    }

    data class BackupContentFilter(
        val value: String,
        val caseSensitive: Boolean = false,
        val isRegex: Boolean = false,
        val includeContacts: Boolean = false
    )

    data class BackupScheduledMessage(
        val date: Long,
        val subId: Int = -1,
        val recipients: List<String> = listOf(),
        val sendAsGroup: Boolean = true,
        val body: String = "",
        val attachments: List<String> = listOf()
    )

    /** A per-contact theme colour, keyed by the contact's address */
    data class BackupRecipientTheme(
        val address: String,
        val color: Int
    )

    data class BackupMessage(
        val type: Int,
        val address: String,
        val date: Long,
        val dateSent: Long,
        val read: Boolean,
        val status: Int,
        val body: String,
        val protocol: Int,
        val serviceCenter: String?,
        val locked: Boolean,
        val subId: Int
    )

    // Subjects to emit our progress events to
    private val backupProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())
    private val restoreProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())

    @Volatile private var stopFlag: Boolean = false

    override fun getDefaultBackupPath(): String {
        return "${Environment.getExternalStorageDirectory()}/QKSMS/Backups"
    }

    override fun getBackupDocumentTree(): DocumentFile? {
        return prefs.backupDirectory.get()
                .takeIf { uri -> uri != Uri.EMPTY }
                ?.let { uri -> DocumentFile.fromTreeUri(context, uri) }
                ?.takeIf { dir -> dir.exists() && dir.canRead() && dir.canWrite() }
    }

    override fun getBackupPathUriForPicker(): Uri {
        return prefs.backupDirectory.get().takeIf { uri -> uri != Uri.EMPTY }
                ?: getDefaultBackupPath().toUri()
    }

    override fun persistBackupDirectory(directory: Uri) {
        context.contentResolver.takePersistableUriPermission(directory,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.backupDirectory.set(directory)

        Timber.v("Updated backup directory: $directory")
    }

    override fun performBackup() {
        // If a backup or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        var messageCount: Int

        // Map all the messages into our object we'll use for the Json mapping
        val backupMessages = Realm.getDefaultInstance().use { realm ->
            // Get the messages from realm
            val messages = realm.where(Message::class.java).sort("date").findAll().createSnapshot()
            messageCount = messages.size

            // Map the messages to the new format
            messages.mapIndexed { index, message ->
                // Update the progress
                backupProgress.onNext(BackupRepository.Progress.Running(messageCount, index))
                messageToBackupMessage(message)
            }
        }

        // Everything else is only stored in QUIK's database or preferences, so save it with the messages
        val backup = Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            Backup(
                    messageCount = messageCount,
                    messages = backupMessages,
                    blockedNumbers = realm.where(BlockedNumber::class.java).findAll()
                            .map { number -> number.address },
                    conversations = realm.where(Conversation::class.java).findAll()
                            .mapNotNull(::conversationToBackupConversation),
                    contentFilters = realm.where(MessageContentFilter::class.java).findAll()
                            .map { filter -> BackupContentFilter(filter.value, filter.caseSensitive, filter.isRegex, filter.includeContacts) },
                    scheduledMessages = realm.where(ScheduledMessage::class.java).findAll()
                            .map { message -> BackupScheduledMessage(message.date, message.subId, message.recipients.toList(),
                                    message.sendAsGroup, message.body, message.attachments.toList()) },
                    recipientThemes = getRecipientThemes(realm),
                    settings = BackupSettings.export(sharedPrefs))
        }

        // Update the status, and set the progress to be indeterminate since we can no longer calculate progress
        backupProgress.onNext(BackupRepository.Progress.Saving())

        // Convert the data to json
        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(backup).toByteArray()

        try {
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(now())
            val inputStream = getBackupDocumentTree()
                    ?.createFile("application/json", "backup-$timestamp.json")
                    ?.let { file -> context.contentResolver.openOutputStream(file.uri) }
                    ?: throw Exception("Failed to open output stream")

            inputStream.use { stream ->
                stream.write(json)
            }
        } catch (e: Exception) {
            Timber.w(e)
        }

        // Mark the task finished, and set it as Idle a second later
        backupProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    private fun messageToBackupMessage(message: Message): BackupMessage = BackupMessage(
            type = message.boxId,
            address = message.address,
            date = message.date,
            dateSent = message.dateSent,
            read = message.read,
            status = message.deliveryStatus,
            body = message.body,
            protocol = 0,
            serviceCenter = null,
            locked = message.locked,
            subId = message.subId
    )

    /**
     * Only conversations that carry something QUIK-specific are saved: a name, archived or pinned
     * state, a draft, a block, group sending turned off, or notification settings of their own
     */
    private fun conversationToBackupConversation(conversation: Conversation): BackupConversation? {
        val addresses = conversation.recipients.map { recipient -> recipient.address }
                .filter { address -> address.isNotBlank() }
        if (addresses.isEmpty()) return null

        val id = conversation.id
        return BackupConversation(
                addresses = addresses,
                name = conversation.name,
                archived = conversation.archived,
                pinned = conversation.pinned,
                sendAsGroup = conversation.sendAsGroup,
                draft = conversation.draft,
                blocked = conversation.blocked,
                blockingClient = conversation.blockingClient,
                blockReason = conversation.blockReason,
                notifications = prefs.notifications(id).get().takeIf { sharedPrefs.contains("notifications_$id") },
                notificationPreviews = prefs.notificationPreviews(id).get().takeIf { sharedPrefs.contains("notification_previews_$id") },
                wakeScreen = prefs.wakeScreen(id).get().takeIf { sharedPrefs.contains("wake_$id") },
                vibration = prefs.vibration(id).get().takeIf { sharedPrefs.contains("vibration$id") },
                ringtone = prefs.ringtone(id).get().takeIf { sharedPrefs.contains("ringtone_$id") }
        ).takeIf { backup -> backup.hasDetails() || backup.settingCount() > 0 }
    }

    /** Per-contact theme colours are stored under the recipient id; save them under the address instead */
    private fun getRecipientThemes(realm: Realm): List<BackupRecipientTheme> {
        return sharedPrefs.all.mapNotNull { (key, value) ->
            val recipientId = key.takeIf { it.startsWith("theme_") }?.removePrefix("theme_")?.toLongOrNull()
                    ?: return@mapNotNull null
            val color = value as? Int ?: return@mapNotNull null
            val address = realm.where(Recipient::class.java).equalTo("id", recipientId).findFirst()?.address
                    ?.takeIf { address -> address.isNotBlank() }
                    ?: return@mapNotNull null
            BackupRecipientTheme(address, color)
        }
    }

    override fun getBackupProgress(): Observable<BackupRepository.Progress> = backupProgress

    @SuppressLint("Recycle") // InputStream is closed by Okio BufferedSource
    override fun parseBackup(uri: Uri): BackupFile {
        val adapter = moshi.adapter(BackupMetadata::class.java)

        val file = DocumentFile.fromSingleUri(context, uri)
                ?: throw IllegalArgumentException("Couldn't open backup file")

        val metadata = context.contentResolver.openInputStream(file.uri)
                ?.source()
                ?.buffer()
                ?.use(adapter::fromJson)
                ?: throw IllegalArgumentException("Couldn't parse backup file")

        return BackupFile(
                date = file.lastModified(),
                messages = metadata.messageCount,
                blockedNumbers = metadata.blockedNumbers.size,
                conversations = metadata.conversations.count { conversation -> conversation.hasDetails() },
                contentFilters = metadata.contentFilters.size,
                scheduledMessages = metadata.scheduledMessages.size,
                settings = metadata.settings.size + metadata.recipientThemes.size +
                        metadata.conversations.sumOf { conversation -> conversation.settingCount() })
    }

    override fun performRestore(uri: Uri, restoreSettings: Boolean) {
        // If a backupFile or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        restoreProgress.onNext(BackupRepository.Progress.Parsing())

        val adapter = moshi.adapter(Backup::class.java)
        val backup = DocumentFile.fromSingleUri(context, uri)
                ?.let { file -> context.contentResolver.openInputStream(file.uri) }
                ?.source()
                ?.buffer()
                ?.use(adapter::fromJson)

        val messageCount = backup?.messages?.size ?: 0
        var errorCount = 0

        backup?.messages?.forEachIndexed { index, message ->
            if (stopFlag) {
                stopFlag = false
                restoreProgress.onNext(BackupRepository.Progress.Idle())
                return
            }

            // Update the progress
            restoreProgress.onNext(BackupRepository.Progress.Running(messageCount, index))

            try {
                val values = contentValuesOf(
                        Telephony.Sms.TYPE to message.type,
                        Telephony.Sms.ADDRESS to message.address,
                        Telephony.Sms.DATE to message.date,
                        Telephony.Sms.DATE_SENT to message.dateSent,
                        Telephony.Sms.READ to message.read,
                        Telephony.Sms.SEEN to 1,
                        Telephony.Sms.STATUS to message.status,
                        Telephony.Sms.BODY to message.body,
                        Telephony.Sms.PROTOCOL to message.protocol,
                        Telephony.Sms.SERVICE_CENTER to message.serviceCenter,
                        Telephony.Sms.LOCKED to message.locked
                )

                if (prefs.canUseSubId.get()) {
                    values.put(Telephony.Sms.SUBSCRIPTION_ID, message.subId)
                }

                context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            } catch (e: Exception) {
                Timber.w(e)
                errorCount++
            }
        }

        if (errorCount > 0) {
            Timber.w(Exception("Failed to backup $errorCount/$messageCount messages"))
        }

        // Sync the messages
        restoreProgress.onNext(BackupRepository.Progress.Syncing())
        syncRepo.syncMessages()

        // Restore blocked numbers, and move the conversations with them back to blocked
        backup?.blockedNumbers?.takeIf { numbers -> numbers.isNotEmpty() }?.let { numbers ->
            blockingRepo.blockNumber(*numbers.toTypedArray())

            val threadIds = numbers.mapNotNull { number -> conversationRepo.getConversation(listOf(number))?.id }
            if (threadIds.isNotEmpty()) {
                conversationRepo.markBlocked(threadIds, Preferences.BLOCKING_MANAGER_QKSMS, null)
            }
        }

        // Restore everything else that only QUIK knows about
        backup?.let { restoreDetails(it, restoreSettings) }

        // Mark the task finished, and set it as Idle a second later
        restoreProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    private fun restoreDetails(backup: Backup, restoreSettings: Boolean) {
        val hasSettings = backup.settings.isNotEmpty() || backup.recipientThemes.isNotEmpty()
        if (backup.conversations.isEmpty() && backup.contentFilters.isEmpty() &&
                backup.scheduledMessages.isEmpty() && !(restoreSettings && hasSettings)) {
            return
        }

        restoreProgress.onNext(BackupRepository.Progress.RestoringSettings())

        backup.conversations.forEach { conversation ->
            try {
                restoreConversation(conversation, restoreSettings)
            } catch (e: Exception) {
                Timber.w(e)
            }
        }

        try {
            restoreContentFilters(backup.contentFilters)
        } catch (e: Exception) {
            Timber.w(e)
        }

        backup.scheduledMessages.forEach { message ->
            try {
                restoreScheduledMessage(message)
            } catch (e: Exception) {
                Timber.w(e)
            }
        }

        if (restoreSettings) {
            try {
                restoreRecipientThemes(backup.recipientThemes)
            } catch (e: Exception) {
                Timber.w(e)
            }
            BackupSettings.import(sharedPrefs, backup.settings)
        }
    }

    /**
     * Applies a conversation's details on top of whatever the sync produced. Values are only ever
     * added, never cleared: a backup can't tell us to un-pin, un-name or un-archive something.
     */
    private fun restoreConversation(backup: BackupConversation, restoreSettings: Boolean) {
        val id = conversationRepo.getOrCreateConversation(backup.addresses, backup.sendAsGroup)?.id
                ?: return

        val current = Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(Conversation::class.java).equalTo("id", id).findFirst()?.let(realm::copyFromRealm)
        } ?: return

        if (backup.name.isNotBlank() && backup.name != current.name) {
            conversationRepo.setConversationName(id, backup.name).blockingAwait()
        }
        if (backup.archived && !current.archived) conversationRepo.markArchived(id)
        if (backup.pinned && !current.pinned) conversationRepo.markPinned(id)
        if (backup.sendAsGroup != current.sendAsGroup) conversationRepo.updateSendAsGroup(id, backup.sendAsGroup)
        if (backup.draft.isNotBlank() && current.draft.isBlank()) conversationRepo.saveDraft(id, backup.draft)
        if (backup.blocked && !current.blocked) {
            conversationRepo.markBlocked(listOf(id), backup.blockingClient ?: Preferences.BLOCKING_MANAGER_QKSMS, backup.blockReason)
        }

        if (restoreSettings) {
            backup.notifications?.let { prefs.notifications(id).set(it) }
            backup.notificationPreviews?.let { prefs.notificationPreviews(id).set(it) }
            backup.wakeScreen?.let { prefs.wakeScreen(id).set(it) }
            backup.vibration?.let { prefs.vibration(id).set(it) }
            backup.ringtone?.let { prefs.ringtone(id).set(it) }
        }
    }

    private fun restoreContentFilters(filters: List<BackupContentFilter>) {
        if (filters.isEmpty()) return

        val existing = Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(MessageContentFilter::class.java).findAll()
                    .map { filter -> BackupContentFilter(filter.value, filter.caseSensitive, filter.isRegex, filter.includeContacts) }
                    .toSet()
        }

        filters.filter { filter -> filter.value.isNotBlank() && filter !in existing }
                .forEach { filter ->
                    contentFilterRepo.createFilter(MessageContentFilterData(
                            filter.value, filter.caseSensitive, filter.isRegex, filter.includeContacts))
                }
    }

    /**
     * Scheduled messages whose time has passed are dropped: sending them on restore would surprise
     * the user. Attachment URIs that can no longer be opened are dropped too, since they point into
     * the old install's storage.
     */
    private fun restoreScheduledMessage(backup: BackupScheduledMessage) {
        if (backup.date <= now() || backup.recipients.isEmpty()) return

        val duplicate = Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(ScheduledMessage::class.java)
                    .equalTo("date", backup.date)
                    .equalTo("body", backup.body)
                    .findAll()
                    .any { message -> message.recipients.toSet() == backup.recipients.toSet() }
        }
        if (duplicate) return

        val attachments = backup.attachments.filter { uri ->
            try {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
        }
        if (attachments.size != backup.attachments.size) {
            Timber.w("Dropped ${backup.attachments.size - attachments.size} unreadable attachment(s) from a restored scheduled message")
        }

        val conversationId = conversationRepo.getOrCreateConversation(backup.recipients, backup.sendAsGroup)?.id ?: 0L
        val message = scheduledMessageRepo.saveScheduledMessage(backup.date, backup.subId, backup.recipients,
                backup.sendAsGroup, backup.body, attachments, conversationId)
        alarmManager.setAlarm(message.date, alarmManager.getScheduledMessageIntent(message.id))
    }

    private fun restoreRecipientThemes(themes: List<BackupRecipientTheme>) {
        if (themes.isEmpty()) return

        Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            val recipients = realm.where(Recipient::class.java).findAll().map { recipient -> recipient.id to recipient.address }
            themes.forEach { theme ->
                recipients.firstOrNull { (_, address) -> phoneNumberUtils.compare(address, theme.address) }
                        ?.let { (recipientId, _) -> prefs.theme(recipientId).set(theme.color) }
            }
        }
    }

    override fun getRestoreProgress(): Observable<BackupRepository.Progress> = restoreProgress

    override fun stopRestore() {
        stopFlag = true
    }

    private fun isBackupOrRestoreRunning(): Boolean {
        return backupProgress.blockingFirst().running || restoreProgress.blockingFirst().running
    }

}