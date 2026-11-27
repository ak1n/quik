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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextNormalizerTest {

    private val spam = "Hey Chris, hope all is well. Its Paul and I'm a direct lender with Blitz."

    @Test
    fun plainText_isUnchanged() {
        val result = MessageTextNormalizer.normalize(spam)
        assertEquals(spam, result.text)
        assertFalse(result.changed)
    }

    @Test
    fun softHyphensAndZeroWidthCharacters_areRemoved() {
        val result = MessageTextNormalizer.normalize("a di­rect le​nd‍er with﻿ Blitz")
        assertEquals("a direct lender with Blitz", result.text)
        assertEquals(4, result.removed)
        assertEquals(0, result.replaced)
    }

    @Test
    fun unusualSpaces_becomePlainSpaces() {
        assertEquals("a direct lender", MessageTextNormalizer.clean("a direct lender"))
    }

    @Test
    fun blankLookingLetters_becomeSpaces() {
        // Braille blank and Hangul filler
        val result = MessageTextNormalizer.normalize("a\u2800direct\u3164lender")
        assertEquals("a direct lender", result.text)
        assertEquals(2, result.removed)
    }

    @Test
    fun cyrillicAndGreekLookalikes_becomeLatin() {
        // а, е, о are Cyrillic; ν is Greek
        val result = MessageTextNormalizer.normalize("а dirеct lеnder with reνоlving LOC")
        assertEquals("a direct lender with revolving LOC", result.text)
        assertEquals(5, result.replaced)
    }

    @Test
    fun fullWidthAndMathematicalLetters_becomeLatin() {
        assertEquals("direct lender", MessageTextNormalizer.clean("ｄｉｒｅｃｔ 𝐥𝐞𝐧𝐝𝐞𝐫"))
    }

    @Test
    fun decorativeCombiningMarks_areRemoved_butAccentsStay() {
        assertEquals("direct", MessageTextNormalizer.clean("d̲i̲r̲e̲c̲t̲"))
        assertEquals("café résumé", MessageTextNormalizer.clean("café résumé"))
        assertEquals("café", MessageTextNormalizer.clean("café"))
    }

    @Test
    fun capitalIForL_isFolded_whereItCanOnlyBeADisguise() {
        // The real message: plain ASCII, "Iender" with a capital I
        val real = "Hey Chris, hope all is well. Its Paul and I'm a direct Iender with Blitz. Are you still looking for long term financing for your business."
        val result = MessageTextNormalizer.normalize(real)
        assertEquals("Hey Chris, hope all is well. Its Paul and I'm a direct lender with Blitz. Are you still looking for long term financing for your business.", result.text)
        assertEquals(1, result.replaced)
        assertTrue(Regex("(?is).*(a direct lender).*").matches(result.text))
        assertFalse(Regex("(?is).*(a direct lender).*").matches(real))

        assertEquals("please click here", MessageTextNormalizer.clean("please cIick here"))
    }

    @Test
    fun legitimateCapitalI_isLeftAlone() {
        for (text in listOf("I'm in. If so, It works. Its fine.", "Get Your Instant Loan Today", "we met in Iowa with Ian",
                "the IRS and my ID", "posted on Instagram", "I'll do it")) {
            assertEquals(text, MessageTextNormalizer.clean(text))
        }
    }

    @Test
    fun lowercaseLForI_andLeetDigits_areFolded() {
        assertEquals("Interest rates: Important news", MessageTextNormalizer.clean("lnterest rates: lmportant news"))
        assertEquals("Loan approval, free money, click", MessageTextNormalizer.clean("L0an appr0val, fr3e m0ney, c|ick"))
        // Only between letters: numbers and word-final runs ("mp3", "fr33") can't be told from real ones
        assertEquals("5 lbs, 10 days, $100, mp3, fr33", MessageTextNormalizer.clean("5 lbs, 10 days, $100, mp3, fr33"))
    }

    @Test
    fun theRealTrick_matchesTheRule() {
        val obfuscated = "Its Paul and I'm а di­rect lеnder with Blitz."
        val result = MessageTextNormalizer.normalize(obfuscated)
        assertTrue(result.changed)
        assertTrue(Regex("(?is).*(a direct lender).*").matches(result.text))
        assertFalse(Regex("(?is).*(a direct lender).*").matches(obfuscated))
    }
}
