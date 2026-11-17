package com.writinghelp.rewrite.data

/**
 * Actions the user can switch on or off in Settings. The first four map to a [RewriteMode];
 * [EXPLAIN] is the "Why?" button shown after Fix Grammar / AI Rewrite in the bubble.
 */
enum class Feature(
    val prefKey: String,
    val title: String,
    val description: String,
    val mode: RewriteMode?
) {
    FIX_GRAMMAR(
        "feature_fix_grammar", "Fix Grammar",
        "Corrects grammar, spelling and punctuation. Bubble and text-selection menu.",
        RewriteMode.FIX_GRAMMAR
    ),
    REWRITE(
        "feature_rewrite", "AI Rewrite",
        "Rewrites your text to read better, with tone chips. Bubble and text-selection menu.",
        RewriteMode.REWRITE
    ),
    TRANSLATE(
        "feature_translate", "Translate",
        "Between your main and second language. Bubble and text-selection menu.",
        RewriteMode.TRANSLATE
    ),
    WRITE(
        "feature_write", "Write for me",
        "Bubble: describe what you need (\"an email to my friend…\") and it writes it into the field.",
        RewriteMode.WRITE
    ),
    EXPLAIN(
        "feature_explain", "Explain changes",
        "Bubble: a \"Why?\" button after Fix Grammar or AI Rewrite shows short bullet points on what changed and why.",
        null
    );

    companion object {
        fun forMode(mode: RewriteMode): Feature = entries.first { it.mode == mode }
    }
}
