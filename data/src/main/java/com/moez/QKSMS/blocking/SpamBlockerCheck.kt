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

import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.util.MessageTextNormalizer
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.realm.Realm
import io.realm.Sort
import javax.inject.Inject

/**
 * Asks SpamBlocker about messages that arrived before it was the blocking manager (or that it let
 * through): one message, the received messages of one conversation, or the latest received
 * message of every conversation.
 *
 * Checks are sent as tests ([SmsScreeningProtocol.KEY_IS_TEST]), so a SpamBlocker that honours the
 * flag neither auto-reports them nor logs them as real messages. Blocking a checked message can
 * then send it again as a real one, so SpamBlocker logs and (if its auto-report is on) reports it.
 */
class SpamBlockerCheck @Inject constructor(
    private val spamBlocker: SpamBlockerBlockingClient,
    private val conversationRepo: ConversationRepository,
    private val prefs: Preferences
) {

    /** A received message to check, copied out of Realm */
    data class Candidate(
        val threadId: Long,
        val title: String,
        val address: String,
        val body: String,
        val subId: Int,
        val date: Long = 0
    ) {
        /** The body with hidden and look-alike characters undone, which is what SpamBlocker is sent */
        val normalized: MessageTextNormalizer.Result by lazy { MessageTextNormalizer.normalize(body) }
    }

    data class Result(val candidate: Candidate, val verdict: SpamBlockerBlockingClient.Verdict) {
        val answer get() = verdict as? SpamBlockerBlockingClient.Verdict.Answer
        val shouldBlock get() = answer?.shouldBlock == true
    }

    /** Progress of a run: [results] so far out of [total] */
    data class Progress(val results: List<Result>, val total: Int) {
        val done get() = results.size == total
    }

    /** True when SpamBlocker is the blocking manager and is installed */
    fun isActive(): Boolean =
            prefs.blockingManager.get() == Preferences.BLOCKING_MANAGER_SB && spamBlocker.isAvailable()

    /** The message with [messageId], if it's one that was received */
    fun candidateForMessage(messageId: Long): Candidate? = Realm.getDefaultInstance().use { realm ->
        realm.refresh()
        val message = realm.where(Message::class.java).equalTo("id", messageId).findFirst()
                ?.takeUnless { it.isMe() }
                ?: return null
        val conversation = realm.where(Conversation::class.java).equalTo("id", message.threadId).findFirst()
        Candidate(message.threadId, conversation?.getTitle() ?: message.address, message.address,
                message.getText(false), message.subId)
    }

    /** The received messages of the conversation [threadId], newest first, at most [limit] */
    fun candidatesForConversation(threadId: Long, limit: Int = CONVERSATION_LIMIT): List<Candidate> =
            Realm.getDefaultInstance().use { realm ->
                realm.refresh()
                val conversation = realm.where(Conversation::class.java).equalTo("id", threadId).findFirst()
                        ?: return listOf()
                realm.where(Message::class.java)
                        .equalTo("threadId", threadId)
                        .sort("date", Sort.DESCENDING)
                        .findAll()
                        .asSequence()
                        .filter { message -> !message.isMe() }
                        .take(limit)
                        .map { message -> candidate(conversation, message) }
                        .toList()
            }

    /**
     * The latest received message of each conversation that isn't blocked or archived. Group
     * conversations are left out (one member's message shouldn't hide the whole group), and so are
     * conversations with a saved contact unless [includeContacts]
     */
    fun candidates(includeContacts: Boolean): List<Candidate> = Realm.getDefaultInstance().use { realm ->
        realm.refresh()
        realm.where(Conversation::class.java)
                .equalTo("blocked", false)
                .equalTo("archived", false)
                .findAll()
                .filter { conversation -> conversation.recipients.size == 1 }
                .filter { conversation -> includeContacts || conversation.recipients.first()?.contact == null }
                .mapNotNull { conversation -> latestReceived(realm, conversation) }
    }

    private fun latestReceived(realm: Realm, conversation: Conversation): Candidate? =
            realm.where(Message::class.java)
                    .equalTo("threadId", conversation.id)
                    .sort("date", Sort.DESCENDING)
                    .findAll()
                    .firstOrNull { message -> !message.isMe() }
                    ?.let { message -> candidate(conversation, message) }

    private fun candidate(conversation: Conversation, message: Message) =
            Candidate(conversation.id, conversation.getTitle(), message.address, message.getText(false), message.subId,
                    message.date)

    /**
     * The newest [perConversation] received messages of the [conversations] most recent unblocked,
     * unarchived one-to-one conversations with no saved contact: what a spam filter has to judge,
     * for writing rules against what actually arrives
     */
    fun recentFromNonContacts(conversations: Int, perConversation: Int): List<Candidate> =
            Realm.getDefaultInstance().use { realm ->
                realm.refresh()
                realm.where(Conversation::class.java)
                        .equalTo("blocked", false)
                        .equalTo("archived", false)
                        .findAll()
                        .filter { conversation -> conversation.recipients.size == 1 && conversation.recipients.first()?.contact == null }
                        .sortedByDescending { conversation -> conversation.date }
                        .take(conversations)
                        .flatMap { conversation ->
                            realm.where(Message::class.java)
                                    .equalTo("threadId", conversation.id)
                                    .sort("date", Sort.DESCENDING)
                                    .findAll()
                                    .asSequence()
                                    .filter { message -> !message.isMe() }
                                    .take(perConversation)
                                    .map { message -> candidate(conversation, message) }
                                    .toList()
                        }
            }

    /** Checks one message as a test */
    fun check(candidate: Candidate): Single<Result> =
            spamBlocker.check(candidate.address, candidate.body, candidate.subId, isTest = true)
                    .map { verdict -> Result(candidate, verdict) }

    /** Checks [candidates] one after another, emitting the results so far after each */
    fun checkAll(candidates: List<Candidate>): Observable<Progress> {
        val results = mutableListOf<Result>()
        return Observable.fromIterable(candidates)
                .concatMapSingle(::check)
                .map { result ->
                    results += result
                    Progress(results.toList(), candidates.size)
                }
                .startWith(Progress(emptyList(), candidates.size))
    }

    /**
     * Moves the conversations of [results] to Blocked with SpamBlocker's reason, as a live block
     * would. With [report], each message whose test check SpamBlocker honoured is first sent again
     * as a real one, so SpamBlocker logs it and its auto-report (if on) reports it. Several results
     * from one conversation block it once, with the reason of the first
     */
    fun block(results: List<Result>, report: Boolean): Completable {
        val resends = results
                .filter { result -> report && result.answer?.testHonoured == true }
                .map { result ->
                    result.candidate.let { c ->
                        spamBlocker.check(c.address, c.body, c.subId, isTest = false).ignoreElement()
                    }
                }
        val blocks = results
                .groupBy { result -> result.candidate.threadId }
                .map { (threadId, results) ->
                    Completable.fromAction {
                        conversationRepo.markBlocked(listOf(threadId), Preferences.BLOCKING_MANAGER_SB,
                                results.firstNotNullOfOrNull { it.answer?.reason })
                    }
                }
        return Completable.concat(resends + blocks)
    }

    companion object {
        /** How many of a conversation's received messages are checked, newest first */
        const val CONVERSATION_LIMIT = 50
    }

}
