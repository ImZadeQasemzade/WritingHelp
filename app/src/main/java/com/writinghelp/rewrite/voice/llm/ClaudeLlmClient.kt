package com.writinghelp.rewrite.voice.llm

import com.writinghelp.rewrite.network.ClaudeApiClient
import com.writinghelp.rewrite.network.ClaudeResult

/** [LlmClient] over WritingHelp's own [ClaudeApiClient], so the keyboard shares its key, model and HTTP stack. */
class ClaudeLlmClient(apiKey: String, model: String) : LlmClient {
    private val api = ClaudeApiClient(apiKey, model)

    override suspend fun complete(system: String, user: String): String =
        when (val r = api.sendMessage(system, user)) {
            is ClaudeResult.Success -> r.text
            is ClaudeResult.Failure -> throw LlmException(
                kind = when {
                    r.isAuthError -> LlmException.Kind.AUTH
                    r.isNetworkError -> LlmException.Kind.OFFLINE
                    else -> LlmException.Kind.OTHER
                },
                message = r.message,
            )
        }
}
