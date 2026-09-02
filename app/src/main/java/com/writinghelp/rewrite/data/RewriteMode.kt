package com.writinghelp.rewrite.data

/** The two entries this app adds to the system text-selection popup. */
enum class RewriteMode(val loadingLabel: String, val supportsRefinement: Boolean) {
    FIX_GRAMMAR(loadingLabel = "Fixing grammar…", supportsRefinement = false),
    REWRITE(loadingLabel = "Rewriting…", supportsRefinement = true)
}
