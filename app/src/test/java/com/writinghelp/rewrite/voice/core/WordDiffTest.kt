package com.writinghelp.rewrite.voice.core

import com.writinghelp.rewrite.voice.core.WordDiff.Kind
import com.writinghelp.rewrite.voice.core.WordDiff.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class WordDiffTest {

    private fun rebuild(segs: List<Segment>, keep: Kind) =
        segs.filter { it.kind == Kind.SAME || it.kind == keep }.joinToString("") { it.text }

    @Test fun changedWordIsMarked() {
        val d = WordDiff.diff("See you on Monday.", "See you on Friday.")
        assertEquals(
            listOf(Segment("See you on ", Kind.SAME), Segment("Monday.", Kind.REMOVED), Segment("Friday.", Kind.ADDED)),
            d,
        )
    }

    @Test fun bothSidesCanBeRebuilt() {
        val old = "hey can u send me the report by monday"
        val new = "Could you please send me the report by Monday?"
        val d = WordDiff.diff(old, new)
        assertEquals(old, rebuild(d, Kind.REMOVED))
        assertEquals(new, rebuild(d, Kind.ADDED))
    }

    @Test fun insertAndDelete() {
        assertEquals(listOf(Segment("new text", Kind.ADDED)), WordDiff.diff("", "new text"))
        assertEquals(listOf(Segment("gone", Kind.REMOVED)), WordDiff.diff("gone", ""))
    }

    @Test fun persianWithZwnj() {
        val d = WordDiff.diff("من به خانه می‌روم", "من به خانه رفتم")
        assertEquals(Segment("من به خانه ", Kind.SAME), d.first())
        assertEquals("می‌روم", d.first { it.kind == Kind.REMOVED }.text)
    }
}
