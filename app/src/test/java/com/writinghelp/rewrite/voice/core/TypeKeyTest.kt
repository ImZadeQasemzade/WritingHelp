package com.writinghelp.rewrite.voice.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TypeKeyTest {
    private val engine = EditorEngine()

    @Test fun spaceAndPunctuation() {
        val f = FakeTextField("Hello|")
        engine.typeKey(f, ",")
        engine.typeKey(f, " ")
        engine.dictate(f, "world", Language.EN)
        engine.typeKey(f, ".")
        assertEquals("Hello, world.|", f.shown)
    }

    @Test fun punctuationReplacesTheSpaceBeforeIt() {
        val f = FakeTextField("Hello |")
        engine.typeKey(f, "?")
        assertEquals("Hello?|", f.shown)
        engine.undo(f) // one tap, one undo step: the space comes back
        assertEquals("Hello |", f.shown)
    }

    @Test fun persianPunctuationAndDictationAfterIt() {
        val f = FakeTextField("سلام|")
        engine.typeKey(f, "،")
        engine.dictate(f, "خوبی", Language.FA)
        engine.typeKey(f, "؟")
        assertEquals("سلام، خوبی؟|", f.shown)
    }

    @Test fun dictationAfterTypedPeriodIsCapitalised() {
        val f = FakeTextField("Done|")
        engine.typeKey(f, ".")
        engine.dictate(f, "next one", Language.EN)
        assertEquals("Done. Next one|", f.shown)
    }
}
