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
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.util.Preferences
import com.f2prateek.rx.preferences2.RxSharedPreferences
import android.preference.PreferenceManager
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
    fun liveScreening_doesNotMarkTheRequestAsATest() {
        installSpamBlocker { Bundle().apply { putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, false) } }

        client.shouldBlock("+15550100300", "hello", -1).test()
        pump()

        assertFalse(requests[0].containsKey(SmsScreeningProtocol.KEY_IS_TEST))
    }

    @Test
    fun testCheck_providerThatEchoesTheFlag_isHonoured() {
        // A SpamBlocker that supports test checks echoes the flag back
        installSpamBlocker { request -> Bundle().apply {
            putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, true)
            putString(SmsScreeningProtocol.KEY_REASON, "number rule")
            if (request.getBoolean(SmsScreeningProtocol.KEY_IS_TEST)) putBoolean(SmsScreeningProtocol.KEY_IS_TEST, true)
        } }

        val test = client.check("+15550100199", "old spam", -1, isTest = true).test()
        pump()

        test.assertValue(SpamBlockerBlockingClient.Verdict.Answer(true, "number rule", testHonoured = true))
        assertTrue(requests[0].getBoolean(SmsScreeningProtocol.KEY_IS_TEST))
        assertEquals("a check asks SpamBlocker even for a number QUIK blocks", 1, requests.size)
    }

    @Test
    fun testCheck_providerThatIgnoresTheFlag_isNotHonoured() {
        installSpamBlocker { Bundle().apply { putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, true) } }

        val test = client.check("+15550100300", "old spam", -1, isTest = true).test()
        pump()

        test.assertValue(SpamBlockerBlockingClient.Verdict.Answer(true, null, testHonoured = false))
    }

    @Test
    fun obfuscatedText_isSentAsItReads() {
        installSpamBlocker { Bundle().apply { putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, false) } }

        // Cyrillic а and е, and a soft hyphen inside "direct"
        client.shouldBlock("+15550100300", "I'm \u0430 di\u00ADrect l\u0435nder", -1).test()
        pump()

        assertEquals("I'm a direct lender", requests[0].getString(SmsScreeningProtocol.KEY_SMS_CONTENT))
    }

    private fun spamCheck(conversations: ConversationRepository): SpamBlockerCheck {
        val shared = PreferenceManager.getDefaultSharedPreferences(app)
        return SpamBlockerCheck(client, conversations, Preferences(app, RxSharedPreferences.create(shared), shared))
    }

    private fun result(threadId: Long, honoured: Boolean) = SpamBlockerCheck.Result(
            SpamBlockerCheck.Candidate(threadId, "Spammer", "+15550100300", "old spam", -1),
            SpamBlockerBlockingClient.Verdict.Answer(true, "number rule", testHonoured = honoured))

    @Test
    fun blockAndReport_resendsHonouredChecksAsRealMessages_thenMovesToBlocked() {
        installSpamBlocker { Bundle().apply { putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, true) } }
        val conversations = mock(ConversationRepository::class.java)

        val test = spamCheck(conversations).block(listOf(result(7, honoured = true), result(8, honoured = false)), report = true).test()
        pump()
        test.awaitTerminalEvent(5, TimeUnit.SECONDS)

        test.assertComplete()
        assertEquals("only the honoured check is sent again", 1, requests.size)
        assertFalse("and as a real message", requests[0].containsKey(SmsScreeningProtocol.KEY_IS_TEST))
        org.mockito.Mockito.verify(conversations).markBlocked(listOf(7L), Preferences.BLOCKING_MANAGER_SB, "number rule")
        org.mockito.Mockito.verify(conversations).markBlocked(listOf(8L), Preferences.BLOCKING_MANAGER_SB, "number rule")
    }

    @Test
    fun blockConversationCheck_resendsEachMessage_butBlocksTheConversationOnce() {
        installSpamBlocker { Bundle().apply { putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, true) } }
        val conversations = mock(ConversationRepository::class.java)

        val test = spamCheck(conversations).block(listOf(result(7, honoured = true), result(7, honoured = true)), report = true).test()
        pump()
        test.awaitTerminalEvent(5, TimeUnit.SECONDS)

        test.assertComplete()
        assertEquals("each flagged message is reported", 2, requests.size)
        org.mockito.Mockito.verify(conversations, org.mockito.Mockito.times(1))
                .markBlocked(listOf(7L), Preferences.BLOCKING_MANAGER_SB, "number rule")
    }

    @Test
    fun blockWithoutReport_sendsNothing() {
        installSpamBlocker { Bundle().apply { putBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, true) } }
        val conversations = mock(ConversationRepository::class.java)

        spamCheck(conversations).block(listOf(result(7, honoured = true)), report = false).test().awaitTerminalEvent(5, TimeUnit.SECONDS)

        assertTrue(requests.isEmpty())
        org.mockito.Mockito.verify(conversations).markBlocked(listOf(7L), Preferences.BLOCKING_MANAGER_SB, "number rule")
    }

    @Test
    fun blockAndUnblock_editQuiksOwnList() {
        client.block(listOf("+15550100400")).blockingAwait()
        client.unblock(listOf("+15550100400")).blockingAwait()

        org.mockito.Mockito.verify(blockingRepo).blockNumber("+15550100400")
        org.mockito.Mockito.verify(blockingRepo).unblockNumbers("+15550100400")
    }

}
