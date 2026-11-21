package com.writinghelp.rewrite.voice.core

/** Decides spacing and capitalisation for a dictated phrase given the text before the cursor. */
object DictationFormatter {

    private val NO_SPACE_BEFORE = setOf('.', ',', '!', '?', ':', ';', ')', ']', '}', '،', '؛', '؟', '…', '%')
    private val SENTENCE_END = setOf('.', '!', '?', '؟', '…')
    private val OPENERS = setOf('(', '[', '{', '"', '«', '“', '\'')
    private val LONE_I = Regex("(?<![\\p{L}'])i(?=$|[\\s,.!?;:']|'m|'ll|'ve|'d)")

    /**
     * @param before text before the cursor, or null if the field can't be read
     *               (then no leading space is added — safest in unknown fields).
     */
    fun format(phrase: String, before: String?, language: Language): String {
        var text = phrase.trim()
        if (text.isEmpty()) return ""
        if (language == Language.FA) text = TextNormalizer.persianLetters(text)

        if (language == Language.EN) {
            text = LONE_I.replace(text, "I")
            if (startsSentence(before)) text = text.replaceFirstChar { it.uppercaseChar() }
        }
        return if (needsLeadingSpace(before, text)) " $text" else text
    }

    fun needsLeadingSpace(before: String?, text: String): Boolean {
        if (before.isNullOrEmpty()) return false
        val last = before.last()
        if (last.isWhitespace() || last == TextNormalizer.ZWNJ || last in OPENERS) return false
        if (text.first() in NO_SPACE_BEFORE) return false
        return true
    }

    /** True at the start of the field, after a newline, or after sentence-ending punctuation. */
    fun startsSentence(before: String?): Boolean {
        if (before == null) return false // unknown: don't guess
        val trimmed = before.trimEnd { it == ' ' || it == '\t' }
        if (trimmed.isEmpty()) return true
        if (before.last() == '\n') return true
        return trimmed.last() in SENTENCE_END
    }
}
