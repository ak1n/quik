package dev.octoshrimpy.quik.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackupSettingsTest {

    private val prefs = FakeSharedPreferences()

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun export_carriesEveryTypeAndOnlyListedKeys() {
        prefs.edit()
                .putBoolean("delivery", true)
                .putInt("sendDelay", 2)
                .putString("signature", "— sent from QUIK")
                .putFloat("showSttOffsetX", 12.5f)
                // not QUIK settings, or device-specific: must stay out
                .putString("backupDirectory", "content://tree/primary")
                .putInt("version", 4370)
                .putBoolean("canUseSubId", false)
                .putInt("theme_12", 0xFF112233.toInt())
                .putBoolean("notifications_55", false)
                .putBoolean("vibration55", false)
                .putString("someOtherLibraryKey", "x")
                .commit()

        val exported = BackupSettings.export(prefs).associateBy { it.key }

        assertEquals(setOf("delivery", "sendDelay", "signature", "showSttOffsetX"), exported.keys)
        assertEquals(BackupSettings.Setting("delivery", BackupSettings.TYPE_BOOLEAN, "true"), exported["delivery"])
        assertEquals(BackupSettings.Setting("sendDelay", BackupSettings.TYPE_INT, "2"), exported["sendDelay"])
        assertEquals(BackupSettings.Setting("signature", BackupSettings.TYPE_STRING, "— sent from QUIK"), exported["signature"])
        assertEquals(BackupSettings.Setting("showSttOffsetX", BackupSettings.TYPE_FLOAT, "12.5"), exported["showSttOffsetX"])
    }

    @Test
    fun import_roundTripsWithTheSameTypes() {
        prefs.edit()
                .putBoolean("delivery", true)
                .putInt("sendDelay", 2)
                .putString("signature", "sig")
                .putFloat("showSttOffsetX", 12.5f)
                .putInt("theme", 0xFF0097A7.toInt())
                .commit()
        val exported = BackupSettings.export(prefs)
        prefs.edit().clear().commit()

        val applied = BackupSettings.import(prefs, exported)

        assertEquals(5, applied)
        val all = prefs.all
        assertEquals(true, all["delivery"])
        assertEquals(2, all["sendDelay"])
        assertEquals("sig", all["signature"])
        assertEquals(12.5f, all["showSttOffsetX"])
        assertEquals(0xFF0097A7.toInt(), all["theme"])
    }

    @Test
    fun import_skipsUnknownKeysAndBadValues() {
        val applied = BackupSettings.import(prefs, listOf(
                BackupSettings.Setting("version", BackupSettings.TYPE_INT, "1"), // not in the list
                BackupSettings.Setting("notifications_9", BackupSettings.TYPE_BOOLEAN, "false"), // per-thread
                BackupSettings.Setting("sendDelay", BackupSettings.TYPE_INT, "lots"), // not a number
                BackupSettings.Setting("delivery", "complex", "1"), // unknown type
                BackupSettings.Setting("unicode", BackupSettings.TYPE_BOOLEAN, "true")
        ))

        assertEquals(1, applied)
        assertNull(prefs.all["version"])
        assertNull(prefs.all["notifications_9"])
        assertFalse(prefs.contains("sendDelay"))
        assertFalse(prefs.contains("delivery"))
        assertTrue(prefs.getBoolean("unicode", false))
    }

    @Test
    fun keyList_hasNoDeviceSpecificOrPerConversationKeys() {
        val banned = listOf("backupDirectory", "canUseSubId", "version", "changelogVersion",
                "didSetReferrer", "hasAskedForNotificationPermission", "nightModeSummary", "night", "sia")
        banned.forEach { key -> assertFalse(key, key in BackupSettings.KEYS) }
        // Per-conversation and per-contact keys carry a thread or recipient id; they travel with the
        // conversation instead (notifAction1..3 are global and allowed)
        val perThread = Regex("^(theme|notifications|notification_previews|wake|ringtone)_\\d+$|^vibration\\d+$")
        assertTrue(BackupSettings.KEYS.none { key -> key.endsWith("_") || perThread.matches(key) })
        assertTrue(perThread.matches("notifications_12") && perThread.matches("vibration12") && !perThread.matches("notifAction1"))
        assertEquals(BackupSettings.KEYS.size, BackupSettings.KEYS.toSet().size)
    }

}
