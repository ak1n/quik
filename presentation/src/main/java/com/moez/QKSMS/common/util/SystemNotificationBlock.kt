/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common.util

import android.app.NotificationManager
import android.os.Build

object SystemNotificationBlock {

    /**
     * True if Android won't show a notification posted to [channelId]: QUIK's notifications are
     * turned off, or the channel is. On Android 11+ a conversation can have its own channel (Android
     * makes one when the conversation's settings are changed from its notification), which then
     * takes precedence over [channelId], so it's looked up by the notification's [shortcutId]
     */
    fun isBlocked(notificationManager: NotificationManager, channelId: String, shortcutId: String?): Boolean {
        if (!notificationManager.areNotificationsEnabled()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        // The two-argument lookup returns the parent channel when there's no conversation channel,
        // but fall back explicitly in case an implementation returns null instead
        val conversationChannel = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && shortcutId != null ->
                notificationManager.getNotificationChannel(channelId, shortcutId)
            else -> null
        }
        val channel = conversationChannel ?: notificationManager.getNotificationChannel(channelId)

        return channel?.importance == NotificationManager.IMPORTANCE_NONE
    }
}
