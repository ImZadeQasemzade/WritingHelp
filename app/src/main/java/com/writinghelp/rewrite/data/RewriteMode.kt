package com.writinghelp.rewrite.data

/** The things WritingHelp can do to text (the first three also appear in the text-selection popup). */
enum class RewriteMode(val title: String, val loadingLabel: String, val supportsRefinement: Boolean) {
    FIX_GRAMMAR(title = "Fix Grammar", loadingLabel = "Fixing grammar…", supportsRefinement = false),
    REWRITE(title = "Rewrite with AI", loadingLabel = "Rewriting…", supportsRefinement = true),
    TRANSLATE(title = "Translate", loadingLabel = "Translating…", supportsRefinement = false),
    /** Bubble only: the input is a request ("an email to my friend…"), not text to transform. */
    WRITE(title = "Write for me", loadingLabel = "Writing…", supportsRefinement = true)
}
