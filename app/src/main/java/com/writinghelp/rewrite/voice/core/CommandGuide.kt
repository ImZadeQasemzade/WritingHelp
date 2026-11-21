package com.writinghelp.rewrite.voice.core

/**
 * What to say, for people. Shown in the keyboard's "?" panel and in WritingHelp settings.
 * Every example must be accepted by [CommandPhrases] (checked by CommandGuideTest), so when
 * you add a phrase there, add its best example here.
 */
object CommandGuide {

    data class Entry(val type: CommandType, val title: String, val en: List<String>, val fa: List<String>)

    val commands: List<Entry> = listOf(
        Entry(CommandType.UNDO, "Undo", listOf("undo", "scratch that"), listOf("برگرد", "واگرد")),
        Entry(CommandType.REDO, "Redo", listOf("redo"), listOf("دوباره")),
        Entry(CommandType.DELETE_WORD, "Delete last word", listOf("delete last word"), listOf("آخرین کلمه رو پاک کن")),
        Entry(
            CommandType.DELETE_SENTENCE, "Delete last sentence",
            listOf("delete last sentence"), listOf("آخرین جمله رو پاک کن"),
        ),
        Entry(
            CommandType.REPLACE, "Replace",
            listOf("replace Monday with Friday", "change cat to dog"),
            listOf("دوشنبه رو به جمعه تغییر بده", "سلام رو با درود عوض کن"),
        ),
        Entry(CommandType.NEW_LINE, "New line", listOf("new line"), listOf("خط جدید")),
        Entry(CommandType.NEW_PARAGRAPH, "New paragraph", listOf("new paragraph"), listOf("پاراگراف جدید")),
        Entry(CommandType.SEND, "Send", listOf("send"), listOf("بفرست")),
        // Said on their own, these switch mode from any mode.
        Entry(CommandType.MODE_AUTO, "Auto mode", listOf("auto"), listOf("خودکار")),
        Entry(CommandType.MODE_DICTATE, "Dictate mode", listOf("dictate"), listOf("دیکته")),
        Entry(CommandType.MODE_COMMAND, "Command mode", listOf("command"), listOf("دستور")),
    )

    /** Needs "Smart voice edits" on in settings. */
    val smartExamples: Pair<List<String>, List<String>> = listOf(
        "make the last sentence more formal",
        "fix the grammar",
        "translate that to Persian",
        "remove the part about Monday",
    ) to listOf(
        "جمله آخر رو رسمی‌تر کن",
        "غلط‌های املایی رو درست کن",
        "این رو به انگلیسی ترجمه کن",
    )

    const val MODES =
        "AUTO: commands run, everything else is typed. DICTATE: everything is typed. " +
            "COMMAND: nothing is typed; only commands (and smart edits) run. " +
            "Say a mode's name on its own to switch to it."
}
