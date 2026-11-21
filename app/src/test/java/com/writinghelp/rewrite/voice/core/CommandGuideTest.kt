package com.writinghelp.rewrite.voice.core

import com.writinghelp.rewrite.voice.llm.SmartPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The help screen must never show a phrase the keyboard doesn't understand. */
class CommandGuideTest {
    private val rules = RuleBasedClassifier()

    @Test fun everyExampleMatchesItsCommand() {
        for (entry in CommandGuide.commands) {
            for (phrase in entry.en + entry.fa) {
                assertEquals("\"$phrase\"", entry.type, rules.match(phrase)?.type)
            }
        }
    }

    @Test fun smartExamplesAreRoutedToTheModel() {
        val (en, fa) = CommandGuide.smartExamples
        for (phrase in en + fa) {
            assertTrue("\"$phrase\"", rules.match(phrase) == null && SmartPipeline.looksLikeInstruction(phrase))
        }
    }
}
