package dev.octoshrimpy.quik.blocking

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import dev.octoshrimpy.quik.compat.SubscriptionManagerCompat
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.repository.BlockingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Drives the client against a fake SpamBlocker service registered with Robolectric, so the whole
 * bind → request → reply → unbind path runs, including what gets sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpamBlockerBlockingClientTest {

    private val app = RuntimeEnvironment.getApplication()
    private val component = ComponentName(SpamBlockerBlockingClient.PACKAGE_NAME, "spam.blocker.service.PublicSMSScreeningService")
    private val blockingRepo: BlockingRepository = mock(BlockingRepository::class.java)
    private val requests = mutableListOf<Bundle>()

    private lateinit var client: SpamBlockerBlockingClient

    @Before
    fun setUp() {
        `when`(blockingRepo.isBlocked("+15550100199")).thenReturn(true)

        val permissions = mock(PermissionManager::class.java)
        `when`(permissions.hasPhone()).thenReturn(false)

        client = SpamBlockerBlockingClient(app, QksmsBlockingClient(blockingRepo),
                SubscriptionManagerCompat(app, permissions), timeoutSeconds = 1)
    }

    private fun installSpamBlocker(reply: ((Bundle) -> Bundle)?) {
        val packageInfo = PackageInfo().apply {
            packageName = SpamBlockerBlockingClient.PACKAGE_NAME
            applicationInfo = ApplicationInfo().apply {
                packageName = SpamBlockerBlockingClient.PACKAGE_NAME
                enabled = true
            }
        }
        shadowOf(app.packageManager).installPackage(packageInfo)
        shadowOf(app.packageManager).addServiceIfNotPresent(component)
        shadowOf(app.packageManager).addIntentFilterForService(component, IntentFilter(SmsScreeningProtocol.ACTION))

        // The fake provider: records the request and answers on the main looper, like a Messenger service would
        val service = Messenger(Handler(Looper.getMainLooper()) { request ->
            requests += Bundle(request.data)
            reply?.let { answer ->
                val result = Message.obtain(null, SmsScreeningProtocol.WHAT_RESULT).apply { data = answer(request.data) }
                request.replyTo.send(result)
            }
            true
        })
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
                Intent(SmsScreeningProtocol.ACTION).setPackage(SpamBlockerBlockingClient.PACKAGE_NAME),
                component, service.binder)
    }

    private fun pump() = repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

    @Test
    fun blockedBySpamBlocker_sendsNumberAndText_andBlocksWithReason() {
        installSpamBlocker { Bundle().apply {
            putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, true)
            putString(SmsScreeningProtocol.KEY_REASON, "content rule: free money")
        } }

        val test = client.shouldBlock("+15550100300", "FREE money, click now", -1).test()
        pump()

        test.assertValue { action -> action is BlockingClient.Action.Block && action.reason == "content rule: free money" }
        assertEquals(1, requests.size)
        assertEquals("+15550100300", requests[0].getString(SmsScreeningProtocol.KEY_NUMBER))
        assertEquals("FREE money, click now", requests[0].getString(SmsScreeningProtocol.KEY_SMS_CONTENT))
        assertFalse("no SIM slot when the subscription is unknown", requests[0].containsKey(SmsScreeningProtocol.KEY_SIM_SLOT))
        assertEquals("service released after the answer", 1, shadowOf(app).unboundServiceConnections.size)
    }

    @Test
    fun allowedBySpamBlocker_unblocks() {
        installSpamBlocker { Bundle().apply { putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, false) } }

        val test = client.shouldBlock("+15550100300", "hi, it's me", -1).test()
        pump()

        test.assertValue { action -> action is BlockingClient.Action.Unblock }
    }

    @Test
    fun numberBlockedInQuik_isBlockedWithoutAskingSpamBlocker() {
        installSpamBlocker { Bundle().apply { putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, false) } }

        val test = client.shouldBlock("+15550100199", "hello", -1).test()
        pump()

        test.assertValue { action -> action is BlockingClient.Action.Block }
        assertTrue("SpamBlocker was not consulted", requests.isEmpty())
    }

    @Test
    fun spamBlockerNotInstalled_leavesTheMessageUnscreened() {
        val test = client.shouldBlock("+15550100300", "hello", -1).test()
        pump()

        test.assertValue { action -> action is BlockingClient.Action.DoNothing }
        assertFalse(client.isAvailable())
    }

    @Test
    fun spamBlockerSilent_timesOutToUnscreened() {
        installSpamBlocker(reply = null) // connected, request received, never answered

        val test = client.shouldBlock("+15550100300", "hello", -1).test()
        pump()
        test.awaitTerminalEvent(5, TimeUnit.SECONDS)

        test.assertValue { action -> action is BlockingClient.Action.DoNothing }
        assertEquals(1, requests.size)
        assertEquals("service released after the timeout", 1, shadowOf(app).unboundServiceConnections.size)
    }

    @Test
    fun blockAndUnblock_editQuiksOwnList() {
        client.block(listOf("+15550100400")).blockingAwait()
        client.unblock(listOf("+15550100400")).blockingAwait()

        org.mockito.Mockito.verify(blockingRepo).blockNumber("+15550100400")
        org.mockito.Mockito.verify(blockingRepo).unblockNumbers("+15550100400")
    }

}
