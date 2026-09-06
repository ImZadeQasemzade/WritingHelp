package com.writinghelp.rewrite.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleTextSpliceTest {

    @Test
    fun replacesMidStringSelection() {
        val r = BubbleTextSplice.splice("hello world", 6, 11, "there")
        assertEquals("hello there", r.newText)
        assertEquals(11, r.newCursorPos)
    }

    @Test
    fun replacesWholeFieldWhenSpanCoversEverything() {
        val r = BubbleTextSplice.splice("hello world", 0, 11, "hi")
        assertEquals("hi", r.newText)
        assertEquals(2, r.newCursorPos)
    }

    @Test
    fun insertsWhenSpanIsEmpty() {
        val r = BubbleTextSplice.splice("abc", 3, 3, "d")
        assertEquals("abcd", r.newText)
        assertEquals(4, r.newCursorPos)
    }

    @Test
    fun normalisesReversedOffsets() {
        val r = BubbleTextSplice.splice("hello world", 11, 6, "there")
        assertEquals("hello there", r.newText)
        assertEquals(11, r.newCursorPos)
    }

    @Test
    fun clampsOffsetsPastEndOfText() {
        val r = BubbleTextSplice.splice("abc", 0, 99, "x")
        assertEquals("x", r.newText)
        assertEquals(1, r.newCursorPos)
    }

    @Test
    fun clampsNegativeOffsets() {
        val r = BubbleTextSplice.splice("abc", -5, 2, "XY")
        assertEquals("XYc", r.newText)
        assertEquals(2, r.newCursorPos)
    }

    @Test
    fun handlesSurrogatePairsByCharOffset() {
        // "a😀b" -> 😀 occupies char indices 1..3 (a surrogate pair)
        val r = BubbleTextSplice.splice("a😀b", 0, 1, "Z")
        assertEquals("Z😀b", r.newText)
        assertEquals(1, r.newCursorPos)
    }

    @Test
    fun emptyReplacementDeletesSpan() {
        val r = BubbleTextSplice.splice("hello world", 5, 11, "")
        assertEquals("hello", r.newText)
        assertEquals(5, r.newCursorPos)
    }
}
