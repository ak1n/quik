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

import java.text.Normalizer

/**
 * Undoes the character tricks spammers use to get keyword filters past: invisible characters
 * inside words (soft hyphens, zero-width spaces and joiners), unusual spaces, decorative combining
 * marks, full-width or "mathematical" alphabets, and letters from other scripts that look like
 * Latin ones (Cyrillic а, е, о…), and plain-ASCII look-alikes: a capital I for an l ("Iender"),
 * digits for letters ("L0an", "fr33"). The result is what the message looks like, written in
 * ordinary characters, so a filter for "a direct lender" matches "a di­rect Iеnder".
 *
 * Only used for matching: the stored message is never changed.
 */
object MessageTextNormalizer {

    /** [text] after normalising; [removed] invisible characters and [replaced] look-alikes */
    data class Result(val text: String, val removed: Int, val replaced: Int) {
        val changed get() = removed + replaced > 0
        val total get() = removed + replaced
    }

    fun clean(text: String): String = normalize(text).text

    fun normalize(text: String): Result {
        val unicode = when (text.all { it.code < 0x80 }) {
            true -> Result(text, 0, 0)
            false -> normalizeUnicode(text)
        }
        return foldAsciiLookalikes(unicode)
    }

    private fun normalizeUnicode(text: String): Result {
        var removed = 0
        var replaced = 0
        val out = StringBuilder(text.length)

        // Compatibility forms first: full-width and mathematical letters, ligatures, odd spaces
        // (NFKC turns no-break and figure spaces into plain ones), then recomposed accents
        val composed = Normalizer.normalize(text, Normalizer.Form.NFKC)
        if (composed != text) replaced += countChanged(text)

        var i = 0
        while (i < composed.length) {
            val cp = composed.codePointAt(i)
            i += Character.charCount(cp)
            when {
                // Zero-width characters, soft hyphen, direction marks, BOM
                Character.getType(cp) == Character.FORMAT.toInt() -> removed++
                // Combining marks that didn't recompose into a letter: underlines, strike-throughs,
                // variation selectors. Accented letters have already been recomposed by NFKC
                Character.getType(cp) == Character.NON_SPACING_MARK.toInt()
                        || Character.getType(cp) == Character.ENCLOSING_MARK.toInt() -> removed++
                // Letters and symbols that draw as nothing, used in place of spaces
                cp in BLANKS -> {
                    removed++
                    out.append(' ')
                }
                else -> {
                    val lookalike = CONFUSABLES[cp]
                    if (lookalike != null) {
                        replaced++
                        out.append(lookalike)
                    } else {
                        out.appendCodePoint(cp)
                    }
                }
            }
        }
        return Result(out.toString(), removed, replaced)
    }

    /**
     * Plain-ASCII characters drawn like other letters in sans-serif fonts, used where they can
     * only be a disguise:
     *  - a capital I after a lowercase letter ("cIick"), or starting a lowercase word in the
     *    middle of a sentence ("a direct Iender"), is an l. Sentence starts, "I'm", acronyms,
     *    Title Case and a few common words and names are left alone;
     *  - a lowercase l starting a word before a consonant ("lnterest", "lmportant") is an I;
     *  - digits and symbols between letters ("L0an", "fr33", "c|ick") are the letters they imitate
     */
    private fun foldAsciiLookalikes(input: Result): Result {
        var replaced = input.replaced
        var text = input.text
        text = INNER_CAPITAL_I.replace(text) { replaced++; "l" }
        text = WORD_CAPITAL_I.replace(text) { match ->
            val word = match.value
            when (word.lowercase() in CAPITAL_I_WORDS) {
                true -> word
                false -> { replaced++; "l" + word.substring(1) }
            }
        }
        text = WORD_LOWER_L.replace(text) { replaced++; "I" }
        text = LEET_RUN.replace(text) { match ->
            replaced += match.value.length
            match.value.map { c -> LEET[c] ?: c }.joinToString("")
        }
        return Result(text, input.removed, replaced)
    }

    private val INNER_CAPITAL_I = Regex("""(?<=\p{Ll})I""")
    // A lowercase word, a space, then I + lowercase letters: mid-sentence, not Title Case
    private val WORD_CAPITAL_I = Regex("""(?<=\b\p{Ll}[\p{Ll}']{0,40} )I\p{Ll}+""")
    private val WORD_LOWER_L = Regex("""\bl(?=[c-df-hj-km-np-tv-xz])""")
    private val LEET_RUN = Regex("""(?<=\p{L})[01357|!$@]+(?=\p{L})""")
    private val LEET = mapOf('0' to 'o', '1' to 'l', '3' to 'e', '5' to 's', '7' to 't', '|' to 'l', '!' to 'i', '$' to 's', '@' to 'a')
    private val CAPITAL_I_WORDS = setOf("in", "if", "it", "its", "is", "into", "ian", "iowa", "idaho", "illinois",
            "indiana", "india", "indian", "ireland", "irish", "italy", "italian", "israel", "iran", "iraq",
            "iceland", "instagram", "intel", "ibiza", "islam", "islamic", "internet", "irs")

    /** How many code points NFKC changes, so the count reflects characters rather than length */
    private fun countChanged(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            if (Normalizer.normalize(s, Normalizer.Form.NFKC) != s) count++
            i += Character.charCount(cp)
        }
        return count
    }

    /** Braille blank, Hangul fillers: visible as a gap, but not spaces */
    private val BLANKS = setOf(0x2800, 0x3164, 0x115F, 0x1160, 0xFFA0)

    /**
     * Letters from other scripts whose usual glyph is the same as a Latin letter's. Mapped to the
     * Latin letter they imitate; only the shapes that are indistinguishable in common fonts
     */
    private val CONFUSABLES: Map<Int, Char> = buildMap {
        fun put(chars: String, latin: Char) {
            var i = 0
            while (i < chars.length) {
                val cp = chars.codePointAt(i)
                put(cp, latin)
                i += Character.charCount(cp)
            }
        }
        // Cyrillic
        put("а", 'a'); put("е", 'e'); put("о", 'o'); put("с", 'c'); put("р", 'p'); put("х", 'x')
        put("у", 'y'); put("і", 'i'); put("ј", 'j'); put("ѕ", 's'); put("ԁ", 'd'); put("ԛ", 'q')
        put("ԝ", 'w'); put("һ", 'h'); put("ӏ", 'l'); put("ԍ", 'g'); put("ꓲ", 'l')
        put("А", 'A'); put("В", 'B'); put("Е", 'E'); put("К", 'K'); put("М", 'M'); put("Н", 'H')
        put("О", 'O'); put("Р", 'P'); put("С", 'C'); put("Т", 'T'); put("Х", 'X'); put("У", 'Y')
        put("Ѕ", 'S'); put("І", 'I'); put("Ј", 'J'); put("Ԝ", 'W'); put("Ғ", 'F')
        // Greek
        put("α", 'a'); put("ο", 'o'); put("ν", 'v'); put("ι", 'i'); put("κ", 'k'); put("ρ", 'p')
        put("τ", 't'); put("υ", 'u'); put("χ", 'x'); put("ϲ", 'c'); put("ϳ", 'j')
        put("Α", 'A'); put("Β", 'B'); put("Ε", 'E'); put("Ζ", 'Z'); put("Η", 'H'); put("Ι", 'I')
        put("Κ", 'K'); put("Μ", 'M'); put("Ν", 'N'); put("Ο", 'O'); put("Ρ", 'P'); put("Τ", 'T')
        put("Υ", 'Y'); put("Χ", 'X')
        // Armenian, Latin extensions and others
        put("ո", 'n'); put("ց", 'g'); put("ս", 'u'); put("օ", 'o'); put("ı", 'i'); put("ɡ", 'g')
        put("ɑ", 'a'); put("ʏ", 'y'); put("ɩ", 'i'); put("ⅼ", 'l'); put("ⅽ", 'c'); put("ⅾ", 'd')
    }
}
