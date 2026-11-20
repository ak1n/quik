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

import dev.octoshrimpy.quik.repository.ParsedEmojiReaction
import timber.log.Timber

/**
 * The regexes that recognise emoji reactions sent as plain text (iOS tapbacks, Google Messages
 * fallbacks). Pure Kotlin, so it can be unit tested with the locale files from the assets folder.
 *
 * @param localeStrings the per-locale pattern strings, in the order they should be tried
 */
class EmojiReactionPatterns(localeStrings: List<Pair<String, EmojiPatternStrings>>) {

    companion object {
        private const val GOOGLE_MESSAGE_REACTION_REGEX =
            "(?s)^ [^​ ]*​([^​]*)​[^​ ]* (.*) [^​ ]* \\Z"
        private const val GOOGLE_MESSAGE_REACTION_REMOVAL_REGEX =
            "(?s)^ [^‌ ]*‌([^‌]*)‌[^‌ ]* (.*) [^‌ ]* \\Z"
    }

    // We use an ordered map to make sure we can test tapback regexes before generic ones
    private val reactionPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf(
        Regex(GOOGLE_MESSAGE_REACTION_REGEX) to { match ->
            ParsedEmojiReaction(match.groupValues[1], match.groupValues[2])
        }
    )
    private val removalPatterns: LinkedHashMap<Regex, (MatchResult) -> ParsedEmojiReaction?> = linkedMapOf(
        Regex(GOOGLE_MESSAGE_REACTION_REMOVAL_REGEX) to { match ->
            ParsedEmojiReaction(match.groupValues[1], match.groupValues[2], isRemoval = true)
        }
    )

    /**
     * Reactions to something that isn't text ("Loved an image"). There is no message text to find
     * the target with, so these can't become reactions; they are only recognised as reaction
     * messages, e.g. so they don't trigger a notification when reaction notifications are off
     */
    private val attachmentReactionPatterns = mutableListOf<Regex>()

    init {
        localeStrings.forEach { (localeTag, strings) ->
            try {
                addPatternsForLocaleStrings(localeTag, strings)
            } catch (e: Exception) {
                Timber.w(e, "Failed to load asset patterns for locale: $localeTag")
            }
        }
        Timber.i("Loaded emoji reaction patterns for locales: ${localeStrings.map { it.first }}")
    }

    /** The reaction (or reaction removal) this text describes, or null if it isn't one */
    fun parse(body: String): ParsedEmojiReaction? {
        val removal = parseRemoval(body)
        if (removal != null) return removal

        for ((pattern, parser) in reactionPatterns) {
            val match = pattern.find(body) ?: continue
            val result = parser(match) ?: continue

            Timber.d("Reaction found with ${result.emoji}")
            return result
        }

        return null
    }

    /**
     * Whether this text is a reaction of any kind: one we can attach to a message, a removal, or a
     * reaction to an attachment. Broader than [parse], which only covers the first two
     */
    fun isReactionText(body: String): Boolean {
        return parse(body) != null || attachmentReactionPatterns.any { pattern -> pattern.matches(body) }
    }

    private fun parseRemoval(body: String): ParsedEmojiReaction? {
        for ((pattern, parser) in removalPatterns) {
            val match = pattern.find(body) ?: continue
            val result = parser(match) ?: continue

            Timber.d("Reaction removal found with ${result.emoji}")
            return result
        }

        return null
    }

    private fun addPatternsForLocaleStrings(localeTag: String, strings: EmojiPatternStrings) {
        // iOS tapbacks (important to add these before generic emoji patterns as the regexes may overlap)
        listOf(
            Triple("❤️", strings.iosHeartAdded, strings.iosHeartRemoved),
            Triple("👍", strings.iosLikeAdded, strings.iosLikeRemoved),
            Triple("👎", strings.iosDislikeAdded, strings.iosDislikeRemoved),
            Triple("😂", strings.iosLaughAdded, strings.iosLaughRemoved),
            Triple("‼️", strings.iosExclamationAdded, strings.iosExclamationRemoved),
            Triple("❓", strings.iosQuestionMarkAdded, strings.iosQuestionMarkRemoved)
        ).forEach { (emoji, added, removed) ->
            added?.takeIf { it.isNotBlank() }?.let {
                reactionPatterns[Regex(it)] = { match -> ParsedEmojiReaction(emoji, match.groupValues[1]) }
            }
            removed?.takeIf { it.isNotBlank() }?.let {
                removalPatterns[Regex(it)] = { match -> ParsedEmojiReaction(emoji, match.groupValues[1], isRemoval = true) }
            }
        }

        // Generic iOS emoji patterns
        strings.iosGenericAdded?.takeIf { it.isNotBlank() }?.let { pattern ->
            reactionPatterns[Regex(pattern)] = { match ->
                // TODO: localize "with a sticker"
                if (match.groupValues.getOrNull(1) == "with a sticker") null
                else ParsedEmojiReaction(match.groupValues[1], match.groupValues[2])
            }
        }
        strings.iosGenericRemoved?.takeIf { it.isNotBlank() }?.let { pattern ->
            removalPatterns[Regex(pattern)] = { match ->
                ParsedEmojiReaction(match.groupValues[1], match.groupValues[2], isRemoval = true)
            }
        }

        strings.iosAttachment?.takeIf { it.isNotBlank() }?.let { pattern ->
            attachmentReactionPatterns += Regex(pattern)
        }

        Timber.d("Loaded emoji regex patterns for $localeTag from assets")
    }

}
