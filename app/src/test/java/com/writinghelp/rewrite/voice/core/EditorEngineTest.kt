package com.writinghelp.rewrite.voice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEngineTest {
    private val engine = EditorEngine()
    private val EN = Language.EN
    private val FA = Language.FA

    private fun applied(r: EditResult) = assertTrue("expected Applied, got $r", r is EditResult.Applied)
    private fun failed(r: EditResult) = assertTrue("expected Failed, got $r", r is EditResult.Failed)

    // ------------------------------------------------------------ dictation

    @Test fun dictationSpacingAndCapitalisation() {
        val f = FakeTextField("|")
        engine.dictate(f, "hello world", EN)
        assertEquals("Hello world|", f.shown)
        engine.dictate(f, "how are you", EN)
        assertEquals("Hello world how are you|", f.shown)
        f.replaceAroundCursor(0, 0, ".")
        engine.dictate(f, "i am fine", EN)
        assertEquals("Hello world how are you. I am fine|", f.shown)
        engine.dictate(f, ", thanks", EN)
        assertEquals("Hello world how are you. I am fine, thanks|", f.shown)
    }

    @Test fun noSpaceAfterNewlineOrAtStart() {
        val f = FakeTextField("Line one\n|")
        engine.dictate(f, "next", EN)
        assertEquals("Line one\nNext|", f.shown)
    }

    @Test fun persianDictationKeepsZwnjAndFixesArabicLetters() {
        val f = FakeTextField("|")
        engine.dictate(f, "من می‌روم", FA)
        engine.dictate(f, "كتاب", FA) // Arabic kaf
        assertEquals("من می‌روم کتاب|", f.shown)
    }

    @Test fun mixedPersianEnglish() {
        val f = FakeTextField("|")
        engine.dictate(f, "این یک", FA)
        engine.dictate(f, "test", EN)
        engine.dictate(f, "است", FA)
        assertEquals("این یک test است|", f.shown)
    }

    @Test fun eachPhraseIsOneUndoStep() {
        val f = FakeTextField("|")
        engine.dictate(f, "one two", EN)
        engine.dictate(f, "three four", EN)
        applied(engine.undo(f))
        assertEquals("One two|", f.shown)
        applied(engine.undo(f))
        assertEquals("|", f.shown)
        failed(engine.undo(f))
        applied(engine.redo(f))
        applied(engine.redo(f))
        assertEquals("One two three four|", f.shown)
        failed(engine.redo(f))
    }

    @Test fun dictationReplacesSelectionAndUndoRestoresIt() {
        val f = FakeTextField("Hello [world]")
        engine.dictate(f, "there", EN)
        assertEquals("Hello there|", f.shown)
        engine.undo(f)
        assertEquals("Hello world|", f.shown)
    }

    @Test fun alternateReplacesLastPhrase() {
        val f = FakeTextField("|")
        engine.dictate(f, "I like", EN)
        engine.dictate(f, "to right", EN)
        applied(engine.replaceLastDictation(f, "to write", EN))
        assertEquals("I like to write|", f.shown)
        engine.undo(f)
        assertEquals("I like to right|", f.shown)
    }

    // ------------------------------------------------------------ commands

    @Test fun deleteLastWord() {
        val f = FakeTextField("Hello big world|")
        applied(engine.deleteLastWord(f))
        assertEquals("Hello big|", f.shown)
        val g = FakeTextField("Hello world. |")
        engine.deleteLastWord(g)
        assertEquals("Hello|", g.shown)
        failed(engine.deleteLastWord(FakeTextField("   |")))
    }

    @Test fun deleteLastSentence() {
        val f = FakeTextField("First one. Second one? Third one|")
        applied(engine.deleteLastSentence(f))
        assertEquals("First one. Second one?|", f.shown)
        applied(engine.deleteLastSentence(f))
        assertEquals("First one.|", f.shown)
        applied(engine.deleteLastSentence(f))
        assertEquals("|", f.shown)
        engine.undo(f)
        assertEquals("First one.|", f.shown)
    }

    @Test fun deleteLastSentencePersianAndNewline() {
        val f = FakeTextField("جمله اول؟ جمله دوم.|")
        engine.deleteLastSentence(f)
        assertEquals("جمله اول؟|", f.shown)
        val g = FakeTextField("Line one\nLine two|")
        engine.deleteLastSentence(g)
        assertEquals("Line one\n|", g.shown)
    }

    @Test fun replaceNearestBeforeCursor() {
        val f = FakeTextField("cat and cat and dog|")
        applied(engine.replace(f, "cat", "bird"))
        assertEquals("cat and bird and dog|", f.shown)
        engine.undo(f)
        assertEquals("cat and cat and dog|", f.shown)
    }

    @Test fun replaceIsCaseInsensitiveAndKeepsCapital() {
        val f = FakeTextField("See you Monday at noon|")
        applied(engine.replace(f, "monday", "tuesday"))
        assertEquals("See you Tuesday at noon|", f.shown)
    }

    @Test fun replacePrefersWholeWords() {
        val f = FakeTextField("an ant ran|")
        engine.replace(f, "an", "one")
        assertEquals("one ant ran|", f.shown)
    }

    @Test fun replacePersianWithZwnjOrSpace() {
        val f = FakeTextField("من می‌روم خانه|")
        // Spoken with a plain space, written with ZWNJ: still found.
        applied(engine.replace(f, "می روم", "می‌آیم"))
        assertEquals("من می‌آیم خانه|", f.shown)
    }

    @Test fun replaceNotFound() {
        failed(engine.replace(FakeTextField("hello|"), "bye", "hi"))
    }

    @Test fun newLineAndSend() {
        val f = FakeTextField("Hi|")
        engine.newLine(f, paragraph = false)
        engine.dictate(f, "there", EN)
        assertEquals("Hi\nThere|", f.shown)
        applied(engine.send(f))
        assertEquals(1, f.actionsPerformed)
        failed(engine.send(FakeTextField("|", hasAction = false)))
    }

    // ------------------------------------------------------------ history safety

    @Test fun undoRefusesWhenTextChangedUnderneath() {
        val f = FakeTextField("|")
        engine.dictate(f, "hello", EN)
        f.replaceAroundCursor(0, 0, " typed by hand")
        failed(engine.undo(f))
        assertEquals("Hello typed by hand|", f.shown)
        // The change is kept, so undo works once the text matches again.
        assertTrue(engine.history.canUndo)
    }

    @Test fun unreadableFieldFallsBackToHistory() {
        val f = FakeTextField("|")
        engine.dictate(f, "hello world", EN)
        f.readable = false
        applied(engine.deleteLastWord(f)) // uses what we last inserted
        f.readable = true
        assertEquals("Hello|", f.shown)
        f.readable = false
        applied(engine.undo(f)) // trusts history
        f.readable = true
        assertEquals("Hello world|", f.shown)
    }

    @Test fun newChangeClearsRedo() {
        val f = FakeTextField("|")
        engine.dictate(f, "a", EN)
        engine.undo(f)
        assertTrue(engine.history.canRedo)
        engine.dictate(f, "b", EN)
        assertFalse(engine.history.canRedo)
    }

    // ------------------------------------------------------------ range edits (smart edits)

    @Test fun rangeEditBeforeCursor() {
        val f = FakeTextField("Hi there. how r u|")
        val ctx = EditContext("Hi there. how r u", "")
        applied(engine.applyRangeEdit(f, ctx, RangeEdit(10, 17, "How are you?"), "Fixed"))
        assertEquals("Hi there. How are you?|", f.shown)
        engine.undo(f)
        assertEquals("Hi there. how r u|", f.shown)
    }

    @Test fun rangeEditAfterCursorKeepsCursor() {
        val f = FakeTextField("Start |middle end")
        val ctx = EditContext("Start ", "middle end")
        applied(engine.applyRangeEdit(f, ctx, RangeEdit(13, 16, "finish"), "Replaced"))
        assertEquals("Start |middle finish", f.shown)
        engine.undo(f)
        assertEquals("Start |middle end", f.shown)
    }

    @Test fun rangeEditSpanningCursor() {
        val f = FakeTextField("one tw|o three")
        val ctx = EditContext("one tw", "o three")
        applied(engine.applyRangeEdit(f, ctx, RangeEdit(4, 7, "2"), "Replaced"))
        assertEquals("one 2| three", f.shown)
        engine.undo(f)
        assertEquals("one tw|o three", f.shown)
    }

    @Test fun rangeEditRejectsStaleContextAndBadOffsets() {
        val f = FakeTextField("current text|")
        failed(engine.applyRangeEdit(f, EditContext("old text", ""), RangeEdit(0, 3, "new"), "x"))
        failed(engine.applyRangeEdit(f, EditContext("current text", ""), RangeEdit(5, 99, "x"), "x"))
        failed(engine.applyRangeEdit(f, EditContext("current text", ""), RangeEdit(-1, 2, "x"), "x"))
        assertEquals("current text|", f.shown)
    }
}
