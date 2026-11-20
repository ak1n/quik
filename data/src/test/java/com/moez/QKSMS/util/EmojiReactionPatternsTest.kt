package dev.octoshrimpy.quik.util

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.octoshrimpy.quik.repository.ParsedEmojiReaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real English pattern file from the assets folder through the pattern matcher. Gradle
 * runs unit tests with the module directory as the working directory.
 */
class EmojiReactionPatternsTest {

    private val english: EmojiPatternStrings = File("src/main/assets/emojis/en.json").readText().let { json ->
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(EmojiPatternStrings::class.java).fromJson(json)!!
    }

    private val patterns = EmojiReactionPatterns(listOf("en" to english))

    @Test
    fun iosTapbacks_parse() {
        assertEquals(ParsedEmojiReaction("❤️", "see you at 6"), patterns.parse("Loved “see you at 6”"))
        assertEquals(ParsedEmojiReaction("👍", "ok"), patterns.parse("Liked “ok”"))
        assertEquals(ParsedEmojiReaction("😂", "lol"), patterns.parse("Laughed at “lol”"))
        assertEquals(ParsedEmojiReaction("🔥", "great news"), patterns.parse("Reacted 🔥 to “great news”"))
    }

    @Test
    fun iosRemovals_parseAsRemovals() {
        assertEquals(ParsedEmojiReaction("👍", "ok", isRemoval = true), patterns.parse("Removed a like from “ok”"))
        assertEquals(ParsedEmojiReaction("🔥", "great news", isRemoval = true), patterns.parse("Removed 🔥 from “great news”"))
    }

    @Test
    fun googleMessagesFallback_parses() {
        // Google Messages wraps the emoji and the quoted text in zero-width markers
        val body = " Reacted ​👍​ to  ok  "
        assertEquals(ParsedEmojiReaction("👍", "ok"), patterns.parse(body))
    }

    @Test
    fun ordinaryText_isNotAReaction() {
        listOf("ok", "Loved it!", "I liked the movie", "Reacted badly to the news", "see you at 6").forEach { text ->
            assertNull(text, patterns.parse(text))
            assertFalse(text, patterns.isReactionText(text))
        }
    }

    @Test
    fun reactionsToAttachments_areRecognisedButNotParsed() {
        listOf("Loved an image", "Liked a video", "Laughed at an image", "Emphasized an attachment",
                "Reacted 😂 to an image", "Reacted ❤️ to a GIF", "Questioned a sticker").forEach { text ->
            assertNull(text, patterns.parse(text))
            assertTrue(text, patterns.isReactionText(text))
        }
    }

    @Test
    fun everyParsedReaction_isAlsoReactionText() {
        listOf("Loved “x”", "Removed a heart from “x”", "Reacted 🔥 to “x”", "Removed 🔥 from “x”").forEach { text ->
            assertTrue(text, patterns.isReactionText(text))
        }
    }

}
