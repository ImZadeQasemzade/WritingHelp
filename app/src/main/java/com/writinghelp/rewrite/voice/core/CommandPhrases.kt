package com.writinghelp.rewrite.voice.core

/**
 * THE command phrase table. To add a phrase, add a pattern string to the right entry.
 *
 * Rules:
 *  - Patterns are matched against the whole phrase after [TextNormalizer.forMatching]
 *    (lower-cased, trailing punctuation removed, Arabic letters mapped to Persian,
 *    ZWNJ turned into a space, whitespace collapsed). Write patterns against that form.
 *  - Capture groups become command args, in order. REPLACE needs exactly two: (old, new).
 *  - Both languages are always active, so an English command works while FA is selected.
 */
object CommandPhrases {

    data class Rule(val type: CommandType, val pattern: Regex)

    private fun r(type: CommandType, vararg patterns: String) =
        patterns.map { Rule(type, Regex("^(?:$it)$", RegexOption.IGNORE_CASE)) }

    val english: List<Rule> = listOf(
        r(CommandType.UNDO, "undo", "undo that", "scratch that"),
        r(CommandType.REDO, "redo", "redo that"),
        r(CommandType.DELETE_WORD, "(?:delete|remove|erase) (?:the )?(?:last )?word", "delete that word"),
        r(CommandType.DELETE_SENTENCE, "(?:delete|remove|erase) (?:the )?(?:last )?sentence", "delete that sentence"),
        r(CommandType.REPLACE, "(?:replace|change) (.+?) (?:with|to|into) (.+)"),
        r(CommandType.NEW_LINE, "new line", "next line"),
        r(CommandType.NEW_PARAGRAPH, "new paragraph", "next paragraph"),
        r(CommandType.SEND, "send", "send it", "send message", "send that"),
        // Mode names on their own (also matched in DICTATE mode, so you can always switch back).
        r(CommandType.MODE_AUTO, "auto", "auto mode", "automatic"),
        r(CommandType.MODE_DICTATE, "dictate", "dictation", "dictate mode", "dictation mode"),
        r(CommandType.MODE_COMMAND, "command", "command mode", "commands"),
    ).flatten()

    // Persian. Spoken "رو" and written "را" are both accepted; ZWNJ has become a space.
    private const val OBJ = "(?: رو| را)?"
    private const val ERASE = "(?:پاک کن|حذف کن|پاکش کن)"

    val persian: List<Rule> = listOf(
        r(CommandType.UNDO, "برگرد", "برگردون", "واگرد", "لغو کن"),
        r(CommandType.REDO, "دوباره", "از نو", "انجام دوباره"),
        r(
            CommandType.DELETE_WORD,
            "آخرین کلمه$OBJ $ERASE",
            "کلمه ?ی? آخر$OBJ $ERASE",
            "کلمه$OBJ $ERASE",
        ),
        r(
            CommandType.DELETE_SENTENCE,
            "آخرین جمله$OBJ $ERASE",
            "جمله ?ی? آخر$OBJ $ERASE",
            "جمله$OBJ $ERASE",
        ),
        r(
            CommandType.REPLACE,
            "(.+?) (?:رو|را) (?:به|با) (.+?) (?:تغییر بده|عوض کن|جایگزین کن|تبدیل کن)",
        ),
        r(CommandType.NEW_LINE, "خط جدید", "خط بعد", "خط بعدی", "سر خط"),
        r(CommandType.NEW_PARAGRAPH, "پاراگراف جدید", "بند جدید"),
        r(CommandType.SEND, "بفرست", "بفرستش", "ارسال کن", "ارسال"),
        r(CommandType.MODE_AUTO, "خودکار", "حالت خودکار", "اتوماتیک"),
        r(CommandType.MODE_DICTATE, "دیکته", "حالت دیکته", "تایپ"),
        r(CommandType.MODE_COMMAND, "دستور", "حالت دستور", "فرمان"),
    ).flatten()

    val all: List<Rule> = english + persian
}
