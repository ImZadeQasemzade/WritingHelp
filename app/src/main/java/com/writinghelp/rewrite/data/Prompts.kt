package com.writinghelp.rewrite.data

/** A quick refinement the user can tap in the Rewrite popup to redo the last result. */
data class RefinementChip(val label: String, val instruction: String)

val REFINEMENT_CHIPS = listOf(
    RefinementChip("Professional", "make the tone more professional and polished"),
    RefinementChip("Friendly", "make the tone warmer and friendlier"),
    RefinementChip("Concise", "make it more concise"),
    RefinementChip("Shorter", "make it noticeably shorter"),
    RefinementChip("Casual", "make the tone more casual and relaxed")
)

object Prompts {

    /**
     * The reply is pasted straight into the user's text field, so the model wraps the result in
     * <output> tags and the app keeps only what's inside them (see ClaudeApiClient). That drops
     * any stray commentary like "The input is in Persian, so I translate it into English."
     */
    private const val TAG_RULES =
        "Put the final text between <output> and </output>, and write nothing outside those " +
            "tags: no preamble, no explanations, no notes about what you did. Inside the tags, " +
            "no quotation marks around the text and no markdown formatting. Preserve line " +
            "breaks and emoji where present."

    /** Fix Grammar and AI Rewrite always answer in the user's main language. */
    private fun outputRules(main: String) =
        "Write the result in $main, in its native script. If the input is in another " +
            "language, translate it into $main as part of this. Do this silently and never " +
            "mention the languages. $TAG_RULES"

    fun systemPromptFor(
        mode: RewriteMode,
        languages: LanguagePair,
        refinementInstruction: String? = null
    ): String =
        when (mode) {
            RewriteMode.FIX_GRAMMAR ->
                "You are a precise grammar and spelling correction assistant for short " +
                    "personal messages (texts, chats, emails). Correct grammar, spelling, and " +
                    "punctuation mistakes while preserving the author's exact meaning, tone, and " +
                    "style. Do not rephrase sentences that are already correct, and do not make " +
                    "the message more formal than the original. ${outputRules(languages.main)}"

            RewriteMode.REWRITE -> buildString {
                append(
                    "You are a writing assistant that rewrites short personal messages to be " +
                        "clearer and better written, while preserving the original meaning and intent. "
                )
                if (!refinementInstruction.isNullOrBlank()) {
                    append("Specifically, ").append(refinementInstruction).append(". ")
                }
                append(outputRules(languages.main))
            }

            RewriteMode.TRANSLATE ->
                "You are a translator for short personal messages (texts, chats, emails). " +
                    "If the input is in ${languages.main}, translate it into " +
                    "${languages.second}. If the input is in any other language, translate it " +
                    "into ${languages.main}. Write the translation in the target language's " +
                    "native script. Keep the meaning, tone, and level of formality of the " +
                    "original, and use natural, everyday phrasing rather than a word-for-word " +
                    "translation. Decide the direction silently and never mention the " +
                    "languages. $TAG_RULES"
        }
}
