package com.writinghelp.rewrite.voice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleBasedClassifierTest {
    private val c = RuleBasedClassifier()

    private fun type(phrase: String) = c.match(phrase)?.type
    private fun auto(phrase: String, lang: Language = Language.EN) =
        c.classifySync(ClassifyRequest(phrase, lang, InputMode.AUTO))

    // ------------------------------------------------------------ English

    @Test fun englishCommands() {
        assertEquals(CommandType.UNDO, type("undo"))
        assertEquals(CommandType.UNDO, type("Undo."))
        assertEquals(CommandType.UNDO, type("scratch that"))
        assertEquals(CommandType.REDO, type("redo"))
        assertEquals(CommandType.DELETE_WORD, type("delete last word"))
        assertEquals(CommandType.DELETE_WORD, type("Delete the last word"))
        assertEquals(CommandType.DELETE_WORD, type("remove word"))
        assertEquals(CommandType.DELETE_SENTENCE, type("delete last sentence"))
        assertEquals(CommandType.DELETE_SENTENCE, type("erase the sentence"))
        assertEquals(CommandType.NEW_LINE, type("new line"))
        assertEquals(CommandType.NEW_PARAGRAPH, type("New paragraph"))
        assertEquals(CommandType.SEND, type("send"))
        assertEquals(CommandType.SEND, type("send it"))
    }

    @Test fun englishReplaceKeepsOriginalCasing() {
        val cmd = c.match("replace Monday with Tuesday")!!
        assertEquals(CommandType.REPLACE, cmd.type)
        assertEquals(listOf("Monday", "Tuesday"), cmd.args)
        assertEquals(listOf("cat", "big dog"), c.match("change cat to big dog")!!.args)
        assertEquals(listOf("hello", "hi"), c.match("Replace \"hello\" with \"hi\".")!!.args)
    }

    @Test fun englishDictationIsNotACommand() {
        assertNull(c.match("I will send you the file tomorrow"))
        assertNull(c.match("please undo the knot"))
        assertNull(c.match("this is a new line of products"))
        assertEquals(KeyboardIntent.Dictation("I will send it later"), auto("I will send it later"))
    }

    // ------------------------------------------------------------ Persian

    @Test fun persianCommands() {
        assertEquals(CommandType.UNDO, type("برگرد"))
        assertEquals(CommandType.REDO, type("دوباره"))
        assertEquals(CommandType.DELETE_WORD, type("آخرین کلمه رو پاک کن"))
        assertEquals(CommandType.DELETE_WORD, type("آخرین کلمه را حذف کن"))
        assertEquals(CommandType.DELETE_WORD, type("کلمه آخر رو پاک کن"))
        assertEquals(CommandType.DELETE_SENTENCE, type("آخرین جمله رو پاک کن"))
        assertEquals(CommandType.DELETE_SENTENCE, type("جمله‌ی آخر رو پاک کن"))
        assertEquals(CommandType.NEW_LINE, type("خط جدید"))
        assertEquals(CommandType.SEND, type("بفرست"))
    }

    @Test fun persianArabicLettersAndPunctuationAreNormalized() {
        // Arabic yeh/kaf, as some recognisers output them.
        assertEquals(CommandType.DELETE_WORD, type("آخرين كلمه رو پاك كن"))
        assertEquals(CommandType.SEND, type("بفرست."))
    }

    @Test fun persianReplace() {
        val cmd = c.match("سلام رو به درود تغییر بده")!!
        assertEquals(CommandType.REPLACE, cmd.type)
        assertEquals(listOf("سلام", "درود"), cmd.args)
        assertEquals(listOf("فردا", "پس فردا"), c.match("فردا را با پس فردا عوض کن")!!.args)
    }

    @Test fun persianReplaceKeepsZwnj() {
        val cmd = c.match("می‌روم رو به می‌آیم تغییر بده")!!
        assertEquals(listOf("می‌روم", "می‌آیم"), cmd.args)
    }

    @Test fun persianDictation() {
        assertNull(c.match("من فردا به خانه برمی‌گردم"))
        assertEquals(
            KeyboardIntent.Dictation("امروز هوا خوب است"),
            auto("امروز هوا خوب است", Language.FA),
        )
    }

    @Test fun englishCommandsWorkInPersianMode() {
        assertEquals(KeyboardIntent.Command(CommandType.UNDO), auto("undo", Language.FA))
    }

    // ------------------------------------------------------------ modes

    @Test fun modes() {
        val dictate = c.classifySync(ClassifyRequest("undo", Language.EN, InputMode.DICTATE))
        assertEquals(KeyboardIntent.Dictation("undo"), dictate)

        val command = c.classifySync(ClassifyRequest("hello there", Language.EN, InputMode.COMMAND))
        assertEquals(KeyboardIntent.Rejected("Not a command"), command)

        val commandOk = c.classifySync(ClassifyRequest("redo", Language.EN, InputMode.COMMAND))
        assertEquals(KeyboardIntent.Command(CommandType.REDO), commandOk)
    }
}
