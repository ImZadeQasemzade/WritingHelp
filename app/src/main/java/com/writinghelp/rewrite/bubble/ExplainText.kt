package com.writinghelp.rewrite.bubble

/** Pure helpers for the "Why?" explanation card (kept Android-free so they're unit-testable). */
object ExplainText {

    private val BULLET_PREFIX = Regex("""^\s*(?:[•·▪●◦*\-–—]+|\d+[.)])\s*""")

    /**
     * Turns Claude's reply into clean bullet strings: one per non-blank line, with any leading
     * bullet marker or numbering ("•", "-", "*", "1.", "2)") removed.
     */
    fun parseBullets(reply: String): List<String> =
        reply.lines()
            .map { it.replace(BULLET_PREFIX, "").trim() }
            .filter { it.isNotEmpty() }

    /** Bullets rendered for the card, one "• " line each. */
    fun render(bullets: List<String>): String = bullets.joinToString("\n") { "• $it" }

    /**
     * Where to put the card (window x, y) so it sits above the bubble when the bubble is in the
     * lower half of the screen and below it otherwise, aligned to the edge the bubble rests on,
     * and always fully on screen.
     */
    fun placeCard(
        screenW: Int,
        screenH: Int,
        bubbleTop: Int,
        bubbleBottom: Int,
        pinnedRight: Boolean,
        cardW: Int,
        cardH: Int,
        gap: Int,
        margin: Int
    ): Pair<Int, Int> {
        val x = (if (pinnedRight) screenW - cardW - margin else margin)
            .coerceIn(0, maxOf(0, screenW - cardW))
        val bubbleCenter = (bubbleTop + bubbleBottom) / 2
        val wantAbove = bubbleCenter > screenH / 2
        val above = bubbleTop - gap - cardH
        val below = bubbleBottom + gap
        val y = when {
            wantAbove && above >= margin -> above
            !wantAbove && below + cardH <= screenH - margin -> below
            // Preferred side doesn't fit: use whichever side has more room.
            bubbleTop > screenH - bubbleBottom -> above
            else -> below
        }.coerceIn(margin.coerceAtMost(screenH), maxOf(margin, screenH - cardH - margin))
        return x to y
    }
}
