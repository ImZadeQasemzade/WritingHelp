package com.writinghelp.rewrite.data

/** Models the user can pick between in Settings. The API id is what's sent to Claude. */
data class ClaudeModelOption(val apiId: String, val displayName: String, val description: String)

/**
 * Built-in list, used until the live list has been fetched from the Models API (no key yet,
 * offline, or the request failed). Settings replaces it with whatever /v1/models returns.
 */
val FALLBACK_MODEL_OPTIONS = listOf(
    ClaudeModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5", descriptionFor("claude-sonnet-5-5")),
    ClaudeModelOption("claude-opus-5-5", "Claude Opus 5.5", descriptionFor("claude-opus-5-5")),
    ClaudeModelOption("claude-haiku-5-5", "Claude Haiku 5.5", descriptionFor("claude-haiku-5-5"))
)

const val DEFAULT_MODEL_ID = "claude-sonnet-5-5"

/** The API doesn't return descriptions, so they're derived from the model family in the id. */
fun descriptionFor(apiId: String): String = when {
    "opus" in apiId -> "Most capable. Best quality rewrites, slower and pricier."
    "sonnet" in apiId -> "Great quality, cheaper and faster than Opus. Default."
    "haiku" in apiId -> "Fastest and cheapest - good for quick grammar fixes."
    else -> "Claude model."
}

/**
 * The model to fall back on when the saved one isn't offered any more: the newest Sonnet in
 * [options] (the API lists newest first), else the first model listed, else [DEFAULT_MODEL_ID].
 */
fun defaultModelFrom(options: List<ClaudeModelOption>): String =
    options.firstOrNull { "sonnet" in it.apiId }?.apiId
        ?: options.firstOrNull()?.apiId
        ?: DEFAULT_MODEL_ID
