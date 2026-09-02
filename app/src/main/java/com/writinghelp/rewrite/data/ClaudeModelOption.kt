package com.writinghelp.rewrite.data

/** Models the user can pick between in Settings. The API id is what's sent to Claude. */
data class ClaudeModelOption(val apiId: String, val displayName: String, val description: String)

val CLAUDE_MODEL_OPTIONS = listOf(
    ClaudeModelOption(
        apiId = "claude-opus-5",
        displayName = "Claude Opus 5",
        description = "Most capable. Default - best quality rewrites."
    ),
    ClaudeModelOption(
        apiId = "claude-sonnet-5",
        displayName = "Claude Sonnet 5",
        description = "Great quality, cheaper and a bit faster than Opus."
    ),
    ClaudeModelOption(
        apiId = "claude-haiku-4-5",
        displayName = "Claude Haiku 4.5",
        description = "Fastest and cheapest - good for quick grammar fixes."
    )
)

val DEFAULT_MODEL_ID = CLAUDE_MODEL_OPTIONS.first().apiId
