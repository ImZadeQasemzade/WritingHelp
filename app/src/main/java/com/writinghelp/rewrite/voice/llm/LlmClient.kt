package com.writinghelp.rewrite.voice.llm

/** Provider-agnostic text completion. Implementations must be cancellable (coroutine timeout). */
interface LlmClient {
    /** @return the model's raw text reply. @throws LlmException */
    suspend fun complete(system: String, user: String): String
}

class LlmException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { OFFLINE, AUTH, REFUSED, OTHER }
}
