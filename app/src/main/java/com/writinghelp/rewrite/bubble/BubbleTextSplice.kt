package com.writinghelp.rewrite.bubble

/**
 * Pure string helper: replace the character span [selStart, selEnd) of [fullText] with
 * [replacement], returning the new text and where the cursor should land afterwards.
 *
 * Offsets are UTF-16 char indices (what [AccessibilityNodeInfo] selection APIs report).
 * They are normalised (start/end may arrive reversed) and clamped into range, so callers
 * can hand over stale selection bounds without crashing.
 */
object BubbleTextSplice {

    data class SpliceResult(val newText: String, val newCursorPos: Int)

    fun splice(fullText: String, selStart: Int, selEnd: Int, replacement: String): SpliceResult {
        val lo = selStart.coerceIn(0, fullText.length).let { a ->
            minOf(a, selEnd.coerceIn(0, fullText.length))
        }
        val hi = maxOf(
            selStart.coerceIn(0, fullText.length),
            selEnd.coerceIn(0, fullText.length)
        )
        val newText = fullText.substring(0, lo) + replacement + fullText.substring(hi)
        return SpliceResult(newText = newText, newCursorPos = lo + replacement.length)
    }
}
