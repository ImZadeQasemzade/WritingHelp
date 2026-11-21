package com.writinghelp.rewrite.voice.core

object TextNormalizer {
    const val ZWNJ = '‌'

    private val BIDI_MARKS = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    private val SPACES = Regex("\\s+")
    private val TRAILING_PUNCT = charArrayOf('.', '!', '?', '؟', '،', ',', '۔', '…')

    /** Map Arabic code points that recognisers sometimes emit to their Persian forms. */
    fun persianLetters(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                'ي', 'ى' -> 'ی' // ي ى -> ی
                'ك' -> 'ک'           // ك -> ک
                else -> c
            }
        )
    }

    /** Canonical form used only for matching commands — never inserted into the field. */
    fun forMatching(phrase: String): String =
        persianLetters(phrase)
            .replace(ZWNJ, ' ')
            .replace(BIDI_MARKS, "")
            .trim()
            .trimEnd(*TRAILING_PUNCT)
            .replace(SPACES, " ")
            .trim()
            .lowercase()

    /** Strip quotes the recogniser might wrap around replace arguments. */
    fun cleanArg(s: String): String =
        s.trim().trim('"', '\'', '«', '»', '“', '”').trim().trimEnd(*TRAILING_PUNCT)
}
