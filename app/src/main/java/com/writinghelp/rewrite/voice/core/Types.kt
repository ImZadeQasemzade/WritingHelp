package com.writinghelp.rewrite.voice.core

/** Recognition / command language. */
enum class Language(val tag: String, val label: String, val isRtl: Boolean) {
    EN("en-US", "EN", false),
    FA("fa-IR", "FA", true);

    fun toggle(): Language = if (this == EN) FA else EN
}

/** How a final phrase is interpreted. */
enum class InputMode {
    /** Rules decide: a recognised command runs, everything else is typed. */
    AUTO,
    /** Everything is typed, even phrases that look like commands. */
    DICTATE,
    /** Everything is treated as a command; unrecognised phrases are rejected, never typed. */
    COMMAND;

    fun next(): InputMode = entries[(ordinal + 1) % entries.size]
}

enum class CommandType(val switchesTo: InputMode? = null) {
    UNDO, REDO, DELETE_WORD, DELETE_SENTENCE, REPLACE, NEW_LINE, NEW_PARAGRAPH, SEND,
    /** Saying just the mode's name switches to it — in every mode, even DICTATE. */
    MODE_AUTO(InputMode.AUTO), MODE_DICTATE(InputMode.DICTATE), MODE_COMMAND(InputMode.COMMAND),
}

/** Text around the cursor, as read from the field. Used by smart (LLM) edits. */
data class EditContext(
    val before: String,
    val after: String,
    val selected: String = "",
) {
    val text: String get() = before + selected + after
    val cursor: Int get() = before.length
}

data class ClassifyRequest(
    val phrase: String,
    val language: Language,
    val mode: InputMode,
    /** Null when the field can't be read or is sensitive (password, incognito). */
    val context: EditContext? = null,
)

/** What the keyboard should do with a final phrase. */
sealed interface KeyboardIntent {
    data class Dictation(val text: String) : KeyboardIntent
    data class Command(val type: CommandType, val args: List<String> = emptyList()) : KeyboardIntent
    /** A range edit proposed by a smart classifier, in offsets of [context]. */
    data class SmartEdit(val edit: RangeEdit, val context: EditContext, val summary: String) : KeyboardIntent
    /** Nothing to do; [message] is shown to the user (e.g. "Not a command"). */
    data class Rejected(val message: String) : KeyboardIntent
}

/** Replace [start, end) of a context string with [text]. Insert = empty range, delete = empty text. */
data class RangeEdit(val start: Int, val end: Int, val text: String)

/**
 * Turns a final phrase into an intent. [RuleBasedClassifier] is the offline implementation;
 * an LLM-backed one implements the same interface and can wrap the rules.
 */
interface IntentClassifier {
    suspend fun classify(request: ClassifyRequest): KeyboardIntent
}
