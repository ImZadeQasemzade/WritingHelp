package com.writinghelp.rewrite.voice.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeSwitchTest {
    private val c = RuleBasedClassifier()

    private fun classify(phrase: String, mode: InputMode, lang: Language = Language.EN) =
        c.classifySync(ClassifyRequest(phrase, lang, mode))

    @Test fun modeNameAloneSwitchesFromEveryMode() {
        for (mode in InputMode.entries) {
            assertEquals(KeyboardIntent.Command(CommandType.MODE_COMMAND), classify("command", mode))
            assertEquals(KeyboardIntent.Command(CommandType.MODE_DICTATE), classify("Dictate.", mode))
            assertEquals(KeyboardIntent.Command(CommandType.MODE_AUTO), classify("auto", mode))
            assertEquals(KeyboardIntent.Command(CommandType.MODE_COMMAND), classify("دستور", mode, Language.FA))
            assertEquals(KeyboardIntent.Command(CommandType.MODE_DICTATE), classify("دیکته", mode, Language.FA))
            assertEquals(KeyboardIntent.Command(CommandType.MODE_AUTO), classify("خودکار", mode, Language.FA))
        }
    }

    @Test fun modeNamesInsideASentenceAreJustText() {
        assertEquals(
            KeyboardIntent.Dictation("I will dictate the letter tomorrow"),
            classify("I will dictate the letter tomorrow", InputMode.AUTO),
        )
        assertEquals(
            KeyboardIntent.Dictation("that command is wrong"),
            classify("that command is wrong", InputMode.DICTATE),
        )
        assertEquals(
            KeyboardIntent.Dictation("این دستور را اجرا کن"),
            classify("این دستور را اجرا کن", InputMode.AUTO, Language.FA),
        )
    }

    @Test fun otherCommandsStillTypedInDictateMode() {
        assertEquals(KeyboardIntent.Dictation("undo"), classify("undo", InputMode.DICTATE))
    }
}
