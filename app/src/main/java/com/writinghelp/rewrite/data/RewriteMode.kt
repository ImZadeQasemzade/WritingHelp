package com.writinghelp.rewrite.data

/** The entries this app adds to the system text-selection popup. */
enum class RewriteMode(val title: String, val loadingLabel: String, val supportsRefinement: Boolean) {
    FIX_GRAMMAR(title = "Fix Grammar", loadingLabel = "Fixing grammar…", supportsRefinement = false),
    REWRITE(title = "Rewrite with AI", loadingLabel = "Rewriting…", supportsRefinement = true),
    TRANSLATE(title = "Translate", loadingLabel = "Translating…", supportsRefinement = false)
}
