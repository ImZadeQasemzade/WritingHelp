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

    private const val OUTPUT_RULES =
        "Output ONLY the rewritten text and nothing else: no preamble, no explanations, " +
            "no quotation marks around it, and no markdown formatting. Keep the same language " +
            "as the input, and preserve line breaks and emoji where present."

    fun systemPromptFor(mode: RewriteMode, refinementInstruction: String? = null): String =
        when (mode) {
            RewriteMode.FIX_GRAMMAR ->
                "You are a precise grammar and spelling correction assistant for short " +
                    "personal messages (texts, chats, emails). Correct grammar, spelling, and " +
                    "punctuation mistakes while preserving the author's exact meaning, tone, and " +
                    "style. Do not rephrase sentences that are already correct, and do not make " +
                    "the message more formal than the original. $OUTPUT_RULES"

            RewriteMode.REWRITE -> buildString {
                append(
                    "You are a writing assistant that rewrites short personal messages to be " +
                        "clearer and better written, while preserving the original meaning and intent. "
                )
                if (!refinementInstruction.isNullOrBlank()) {
                    append("Specifically, ").append(refinementInstruction).append(". ")
                }
                append(OUTPUT_RULES)
            }
        }
}
