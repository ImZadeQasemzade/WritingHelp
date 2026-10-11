package com.writinghelp.rewrite.data

/**
 * Languages offered in Settings. The name is what's put into the prompt, so it's the
 * English name Claude knows the language by.
 */
val LANGUAGES = listOf(
    "English",
    "Persian",
    "Arabic",
    "Chinese",
    "Dutch",
    "French",
    "German",
    "Hindi",
    "Italian",
    "Japanese",
    "Korean",
    "Portuguese",
    "Russian",
    "Spanish",
    "Turkish",
    "Urdu"
)

const val DEFAULT_MAIN_LANGUAGE = "English"
const val DEFAULT_SECOND_LANGUAGE = "Persian"

/**
 * The user's two languages. [main] is what Fix Grammar and AI Rewrite write in; Translate
 * turns [main] into [second] and anything else into [main].
 */
data class LanguagePair(val main: String, val second: String)
