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
package dev.octoshrimpy.quik.blocking

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import dev.octoshrimpy.quik.common.util.extensions.isInstalled
import dev.octoshrimpy.quik.compat.SubscriptionManagerCompat
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.subjects.SingleSubject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Screens incoming messages with SpamBlocker (https://github.com/aj3423/SpamBlocker) through the
 * SMS Screening Protocol, so SpamBlocker decides and QUIK notifies.
 *
 * QUIK's own blocked numbers stay in force: a number blocked in QUIK is blocked without asking
 * SpamBlocker, and Block/Unblock in QUIK edit QUIK's list as usual. SpamBlocker gets the number,
 * the message text (so its content rules apply) and the SIM slot. If SpamBlocker is missing,
 * disabled or doesn't answer within [timeoutSeconds], the message is treated as unscreened
 * ([BlockingClient.Action.DoNothing]), never dropped.
 */
class SpamBlockerBlockingClient(
    private val context: Context,
    private val qksmsBlockingClient: QksmsBlockingClient,
    private val subscriptionManager: SubscriptionManagerCompat,
    private val timeoutSeconds: Long
) : BlockingClient {

    @Inject constructor(
        context: Context,
        qksmsBlockingClient: QksmsBlockingClient,
        subscriptionManager: SubscriptionManagerCompat
    ) : this(context, qksmsBlockingClient, subscriptionManager, DEFAULT_TIMEOUT_SECONDS)

    companion object {
        const val PACKAGE_NAME = "spam.blocker"
        const val DEFAULT_TIMEOUT_SECONDS = 10L
    }

    /** What the provider said, or that it couldn't be asked */
    sealed class Verdict {
        /** [testHonoured]: this was a test check and the provider confirmed it treated it as one */
        data class Answer(
            val shouldBlock: Boolean,
            val reason: String?,
            val testHonoured: Boolean = false
        ) : Verdict()
        object Unavailable : Verdict()
    }

    override fun isAvailable(): Boolean = context.isInstalled(PACKAGE_NAME)

    override fun getClientCapability() = BlockingClient.Capability.BLOCK_WITHOUT_PERMISSION

    override fun shouldBlock(address: String): Single<BlockingClient.Action> = screen(address, "", null)

    override fun shouldBlock(address: String, body: String, subId: Int): Single<BlockingClient.Action> =
            screen(address, body, simSlotFor(subId))

    override fun isBlacklisted(address: String): Single<BlockingClient.Action> = screen(address, "", null)

    override fun block(addresses: List<String>): Completable = qksmsBlockingClient.block(addresses)

    override fun unblock(addresses: List<String>): Completable = qksmsBlockingClient.unblock(addresses)

    override fun openSettings() {
        context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)?.let(context::startActivity)
    }

    private fun screen(address: String, body: String, simSlot: Int?): Single<BlockingClient.Action> {
        return qksmsBlockingClient.isBlacklisted(address).flatMap { own ->
            when (own) {
                is BlockingClient.Action.Block -> Single.just(own)
                else -> ask(address, body, simSlot).map { verdict ->
                    when (verdict) {
                        is Verdict.Answer -> when (verdict.shouldBlock) {
                            true -> BlockingClient.Action.Block(verdict.reason)
                            false -> BlockingClient.Action.Unblock
                        }
                        is Verdict.Unavailable -> BlockingClient.Action.DoNothing
                    }
                }
            }
        }
    }

    /**
     * Asks SpamBlocker about a message that has already arrived, without QUIK's own list in the way.
     * [isTest] asks SpamBlocker to treat it as a test; see [SmsScreeningProtocol.KEY_IS_TEST]
     */
    fun check(address: String, body: String, subId: Int, isTest: Boolean): Single<Verdict> =
            ask(address, body, simSlotFor(subId), isTest)

    /** One bind-ask-unbind round trip, with a timeout so a silent provider can't stall a receive */
    fun ask(address: String, body: String, simSlot: Int?, isTest: Boolean = false): Single<Verdict> {
        if (!isAvailable()) return Single.just(Verdict.Unavailable)

        return ScreeningRequest(context, address, body, simSlot, isTest).send()
                .timeout(timeoutSeconds, TimeUnit.SECONDS)
                .onErrorReturn { e ->
                    Timber.w(e, "SpamBlocker didn't answer; leaving the message unscreened")
                    Verdict.Unavailable
                }
    }

    private fun simSlotFor(subId: Int): Int? = try {
        subscriptionManager.activeSubscriptionInfoList
                .firstOrNull { info -> info.subscriptionId == subId }
                ?.simSlotIndex
    } catch (e: Exception) {
        null
    }

    private class ScreeningRequest(
        private val context: Context,
        private val address: String,
        private val body: String,
        private val simSlot: Int?,
        private val isTest: Boolean
    ) : ServiceConnection {

        private val subject: SingleSubject<Verdict> = SingleSubject.create()
        private val bound = AtomicBoolean(false)

        fun send(): Single<Verdict> {
            val intent = Intent(SmsScreeningProtocol.ACTION).setPackage(PACKAGE_NAME)
            val binding = try {
                context.bindService(intent, this, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                Timber.w(e)
                false
            }

            if (binding) {
                bound.set(true)
            } else {
                subject.onSuccess(Verdict.Unavailable)
            }

            // Runs after the answer, the timeout or the error: always let go of the service
            return subject.doFinally { unbind() }
        }

        private fun unbind() {
            if (bound.compareAndSet(true, false)) {
                try {
                    context.unbindService(this)
                } catch (e: IllegalArgumentException) {
                    // Already gone
                }
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val request = Message.obtain(null, SmsScreeningProtocol.WHAT_SCREEN).apply {
                data = Bundle().apply {
                    putString(SmsScreeningProtocol.KEY_NUMBER, address)
                    putString(SmsScreeningProtocol.KEY_SMS_CONTENT, body)
                    simSlot?.let { slot -> putInt(SmsScreeningProtocol.KEY_SIM_SLOT, slot) }
                    if (isTest) putBoolean(SmsScreeningProtocol.KEY_IS_TEST, true)
                }
                replyTo = Messenger(Handler(Looper.getMainLooper()) { reply ->
                    if (reply.what == SmsScreeningProtocol.WHAT_RESULT) {
                        val data = reply.data
                        subject.onSuccess(Verdict.Answer(
                                shouldBlock = data.getBoolean(SmsScreeningProtocol.KEY_SHOULD_BLOCK, false),
                                reason = data.getString(SmsScreeningProtocol.KEY_REASON),
                                testHonoured = isTest && data.getBoolean(SmsScreeningProtocol.KEY_IS_TEST, false)))
                    }
                    true
                })
            }

            try {
                Messenger(service).send(request)
            } catch (e: RemoteException) {
                Timber.w(e)
                subject.onSuccess(Verdict.Unavailable)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            subject.onSuccess(Verdict.Unavailable)
        }

        override fun onNullBinding(name: ComponentName) {
            subject.onSuccess(Verdict.Unavailable)
        }

        override fun onBindingDied(name: ComponentName) {
            subject.onSuccess(Verdict.Unavailable)
        }
    }

}
