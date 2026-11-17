package com.writinghelp.rewrite.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplainTextTest {

    @Test
    fun stripsBulletMarkersAndBlankLines() {
        val reply = "• 'has went' → 'has gone': past participle after 'has'\n\n- Added a comma\n* Fixed spelling of 'recieve'\n"
        assertEquals(
            listOf(
                "'has went' → 'has gone': past participle after 'has'",
                "Added a comma",
                "Fixed spelling of 'recieve'"
            ),
            ExplainText.parseBullets(reply)
        )
    }

    @Test
    fun stripsNumbering() {
        assertEquals(listOf("First", "Second"), ExplainText.parseBullets("1. First\n2) Second"))
    }

    @Test
    fun keepsHyphenInsideText() {
        assertEquals(listOf("well-known is hyphenated"), ExplainText.parseBullets("• well-known is hyphenated"))
    }

    @Test
    fun rendersOneBulletPerLine() {
        assertEquals("• a\n• b", ExplainText.render(listOf("a", "b")))
    }

    @Test
    fun placesCardAboveBubbleInLowerHalf() {
        val (x, y) = ExplainText.placeCard(
            screenW = 1000, screenH = 2000, bubbleTop = 1500, bubbleBottom = 1600,
            pinnedRight = true, cardW = 800, cardH = 400, gap = 10, margin = 20
        )
        assertEquals(180, x)          // right-aligned with margin
        assertEquals(1090, y)         // 1500 - 10 - 400
    }

    @Test
    fun placesCardBelowBubbleInUpperHalf() {
        val (x, y) = ExplainText.placeCard(
            screenW = 1000, screenH = 2000, bubbleTop = 300, bubbleBottom = 400,
            pinnedRight = false, cardW = 800, cardH = 400, gap = 10, margin = 20
        )
        assertEquals(20, x)           // left-aligned with margin
        assertEquals(410, y)          // 400 + 10
    }

    @Test
    fun fallsBackToOtherSideWhenPreferredDoesNotFit() {
        // Bubble in lower half but card too tall to fit above -> goes below if there's more room there.
        val (_, y) = ExplainText.placeCard(
            screenW = 1000, screenH = 2000, bubbleTop = 1050, bubbleBottom = 1150,
            pinnedRight = true, cardW = 800, cardH = 1100, gap = 10, margin = 20
        )
        // above = 1050-10-1100 = -60 (doesn't fit); bubbleTop(1050) > room below(850) -> above, clamped to margin
        assertEquals(20, y)
    }

    @Test
    fun clampsCardOnScreen() {
        val (_, y) = ExplainText.placeCard(
            screenW = 1000, screenH = 2000, bubbleTop = 1900, bubbleBottom = 2000,
            pinnedRight = true, cardW = 800, cardH = 400, gap = 10, margin = 20
        )
        assertEquals(1490, y)
    }
}
