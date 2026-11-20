package dev.octoshrimpy.quik.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.Backup
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupContentFilter
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupConversation
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupMessage
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupMetadata
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupRecipientTheme
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl.BackupScheduledMessage
import dev.octoshrimpy.quik.util.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup file is plain JSON written through Moshi. These tests pin the format: a full backup
 * survives a round trip, backups from older versions still parse, and the metadata view reads the
 * counts the details dialog shows.
 */
class BackupFormatTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val message = BackupMessage(type = 1, address = "+15550100199", date = 1_700_000_000_000,
            dateSent = 1_700_000_000_000, read = true, status = -1, body = "hi", protocol = 0,
            serviceCenter = null, locked = false, subId = -1)

    private val fullBackup = Backup(
            messageCount = 1,
            messages = listOf(message),
            blockedNumbers = listOf("+15550100300"),
            conversations = listOf(
                    BackupConversation(addresses = listOf("+15550100199", "+15550100200"), name = "Family",
                            pinned = true, notifications = false, ringtone = "content://settings/system/notification_sound"),
                    BackupConversation(addresses = listOf("+15550100300"), archived = true, draft = "later",
                            blocked = true, blockingClient = 0, blockReason = "spam")),
            contentFilters = listOf(BackupContentFilter("free money", caseSensitive = false, isRegex = false, includeContacts = false)),
            scheduledMessages = listOf(BackupScheduledMessage(date = 1_800_000_000_000, subId = 1,
                    recipients = listOf("+15550100199"), sendAsGroup = false, body = "happy birthday",
                    attachments = listOf("content://media/external/images/1"))),
            recipientThemes = listOf(BackupRecipientTheme("+15550100199", 0xFF112233.toInt())),
            settings = listOf(BackupSettings.Setting("sendDelay", BackupSettings.TYPE_INT, "2"),
                    BackupSettings.Setting("signature", BackupSettings.TYPE_STRING, "sig")))

    @Test
    fun fullBackup_survivesARoundTrip() {
        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(fullBackup)

        assertEquals(fullBackup, adapter.fromJson(json))
    }

    @Test
    fun legacyBackup_parsesWithEmptyExtras() {
        // The format written by QKSMS 3.x and QUIK before this change
        val legacy = """{"messageCount":1,"messages":[{"type":1,"address":"+15550100199","date":1700000000000,
            "dateSent":1700000000000,"read":true,"status":-1,"body":"hi","protocol":0,"serviceCenter":null,
            "locked":false,"subId":-1}]}"""

        val backup = moshi.adapter(Backup::class.java).fromJson(legacy)!!

        assertEquals(listOf(message), backup.messages)
        assertTrue(backup.blockedNumbers.isEmpty())
        assertTrue(backup.conversations.isEmpty())
        assertTrue(backup.contentFilters.isEmpty())
        assertTrue(backup.scheduledMessages.isEmpty())
        assertTrue(backup.recipientThemes.isEmpty())
        assertTrue(backup.settings.isEmpty())
    }

    @Test
    fun metadata_readsTheCountsWithoutTheMessages() {
        val json = moshi.adapter(Backup::class.java).toJson(fullBackup)

        val metadata = moshi.adapter(BackupMetadata::class.java).fromJson(json)!!

        assertEquals(1, metadata.messageCount)
        assertEquals(1, metadata.blockedNumbers.size)
        assertEquals(2, metadata.conversations.size)
        assertEquals(1, metadata.contentFilters.size)
        assertEquals(1, metadata.scheduledMessages.size)
        assertEquals(1, metadata.recipientThemes.size)
        assertEquals(2, metadata.settings.size)
    }

    @Test
    fun conversationEntry_knowsWhatItCarries() {
        val plain = BackupConversation(addresses = listOf("+15550100199"))
        assertFalse(plain.hasDetails())
        assertEquals(0, plain.settingCount())

        assertTrue(BackupConversation(addresses = listOf("x"), name = "Family").hasDetails())
        assertTrue(BackupConversation(addresses = listOf("x"), sendAsGroup = false).hasDetails())
        assertTrue(BackupConversation(addresses = listOf("x"), draft = "d").hasDetails())

        val onlySettings = BackupConversation(addresses = listOf("x"), notifications = false, vibration = true)
        assertFalse(onlySettings.hasDetails())
        assertEquals(2, onlySettings.settingCount())
    }

    @Test
    fun unknownFieldsFromANewerVersion_areIgnored() {
        val json = """{"messageCount":0,"messages":[],"futureSection":[{"a":1}],"settings":[]}"""

        val backup = moshi.adapter(Backup::class.java).fromJson(json)!!

        assertEquals(0, backup.messageCount)
    }

}
