package dev.octoshrimpy.quik.common.util

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the app's own one loads Realm's native library, which isn't available on the JVM.
@Config(application = Application::class)
class SystemNotificationBlockTest {

    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun channel(id: String, importance: Int) =
        manager.createNotificationChannel(NotificationChannel(id, id, importance))

    @Test
    fun openChannel_isNotBlocked() {
        channel("notifications_5", NotificationManager.IMPORTANCE_HIGH)
        assertFalse(SystemNotificationBlock.isBlocked(manager, "notifications_5", "5"))
    }

    @Test
    fun channelTurnedOff_isBlocked() {
        channel("notifications_5", NotificationManager.IMPORTANCE_NONE)
        assertTrue(SystemNotificationBlock.isBlocked(manager, "notifications_5", "5"))
    }

    @Test
    fun appNotificationsOff_isBlocked() {
        channel("notifications_5", NotificationManager.IMPORTANCE_HIGH)
        shadowOf(manager).setNotificationsEnabled(false)
        assertTrue(SystemNotificationBlock.isBlocked(manager, "notifications_5", "5"))
    }

    @Test
    fun missingChannel_isNotBlocked() {
        assertFalse(SystemNotificationBlock.isBlocked(manager, "notifications_9", null))
    }
}
