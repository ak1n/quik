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
package dev.octoshrimpy.quik.util

import android.content.SharedPreferences

/**
 * Copies QUIK's own settings in and out of the backup file.
 *
 * Only the keys listed in [KEYS] are exported: these are the user-facing settings from
 * [Preferences]. Internal markers (version, changelog, referrer), device-specific values
 * (the backup folder URI, dual-SIM capability) and the per-conversation / per-contact keys
 * (which the backup stores next to their conversation, keyed by address rather than by
 * thread id) are left out on purpose.
 */
object BackupSettings {

    /** One setting, with its type spelled out so a restore can write it back exactly. */
    data class Setting(
        val key: String,
        val type: String,
        val value: String
    )

    const val TYPE_BOOLEAN = "boolean"
    const val TYPE_INT = "int"
    const val TYPE_LONG = "long"
    const val TYPE_FLOAT = "float"
    const val TYPE_STRING = "string"

    val KEYS: List<String> = listOf(
        // Appearance
        "theme", "nightMode", "nightStart", "nightEnd", "black", "autoColor", "systemFont",
        "textSize", "showStt", "showSttOffsetX", "showSttOffsetY",
        // Notifications (the global defaults; per-conversation values travel with the conversation)
        "notifications", "notification_previews", "wake", "vibration", "ringtone",
        "notifAction1", "notifAction2", "notifAction3", "qkreply", "qkreplyTapDismiss",
        // Blocking
        "blockingManager", "drop", "silentNotContact",
        // Sending and messages
        "sendAsGroup", "sendDelay", "swipeRight", "swipeLeft", "autoEmoji", "delivery",
        "signature", "unicode", "mobileOnly", "autoDelete", "longAsMms", "mmsSize",
        "messageLinkHandling", "unreadAtTop", "autoDeduplicateMessages",
        // Misc
        "disableScreenshots", "logging"
    )

    fun export(prefs: SharedPreferences): List<Setting> {
        val all = prefs.all
        return KEYS.mapNotNull { key ->
            when (val value = all[key]) {
                is Boolean -> Setting(key, TYPE_BOOLEAN, value.toString())
                is Int -> Setting(key, TYPE_INT, value.toString())
                is Long -> Setting(key, TYPE_LONG, value.toString())
                is Float -> Setting(key, TYPE_FLOAT, value.toString())
                is String -> Setting(key, TYPE_STRING, value)
                else -> null // unset, or a type the backup doesn't carry
            }
        }
    }

    /**
     * Writes the settings back. Unknown keys and malformed values are skipped rather than
     * failing the restore. Returns how many settings were applied.
     */
    fun import(prefs: SharedPreferences, settings: List<Setting>): Int {
        val editor = prefs.edit()
        var applied = 0
        settings.filter { setting -> setting.key in KEYS }.forEach { setting ->
            val ok = when (setting.type) {
                TYPE_BOOLEAN -> setting.value.toBooleanStrictOrNull()?.let { editor.putBoolean(setting.key, it) }
                TYPE_INT -> setting.value.toIntOrNull()?.let { editor.putInt(setting.key, it) }
                TYPE_LONG -> setting.value.toLongOrNull()?.let { editor.putLong(setting.key, it) }
                TYPE_FLOAT -> setting.value.toFloatOrNull()?.let { editor.putFloat(setting.key, it) }
                TYPE_STRING -> editor.putString(setting.key, setting.value)
                else -> null
            }
            if (ok != null) applied++
        }
        editor.apply()
        return applied
    }

}
