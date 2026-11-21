package com.writinghelp.rewrite.voice.core

/** In-memory text field. `|` in [initial] marks the cursor; `[` `]` mark a selection. */
class FakeTextField(
    initial: String = "|",
    var readable: Boolean = true,
    var hasAction: Boolean = true,
) : TextField {
    val sb = StringBuilder()
    var selStart = 0
    var selEnd = 0
    var actionsPerformed = 0

    init {
        val s = initial.indexOf('[')
        if (s >= 0) {
            val e = initial.indexOf(']')
            sb.append(initial.substring(0, s)).append(initial.substring(s + 1, e)).append(initial.substring(e + 1))
            selStart = s; selEnd = e - 1
        } else {
            val c = initial.indexOf('|').also { require(it >= 0) { "missing cursor" } }
            sb.append(initial.removeRange(c, c + 1))
            selStart = c; selEnd = c
        }
    }

    val text: String get() = sb.toString()

    /** Text with `|` at the cursor (selection collapsed in tests that use this). */
    val shown: String get() = sb.toString().let { it.substring(0, selEnd) + "|" + it.substring(selEnd) }

    override fun textBeforeCursor(max: Int): String? =
        if (!readable) null else sb.substring(maxOf(0, selStart - max), selStart)

    override fun textAfterCursor(max: Int): String? =
        if (!readable) null else sb.substring(selEnd, minOf(sb.length, selEnd + max))

    override fun selectedText(): String? = if (!readable) null else sb.substring(selStart, selEnd)

    override fun replaceAroundCursor(deleteBefore: Int, deleteAfter: Int, insertBefore: String, insertAfter: String) {
        // Matches InputConnection: delete around the selection, then commit replaces the selection.
        val a = maxOf(0, selStart - deleteBefore)
        val b = minOf(sb.length, selEnd + deleteAfter)
        sb.delete(selEnd, b)
        sb.delete(a, selStart)
        val s = a
        val e = s + (selEnd - selStart)
        sb.replace(s, e, insertBefore + insertAfter)
        selStart = s + insertBefore.length
        selEnd = selStart
    }

    override fun performEditorAction(): Boolean {
        if (hasAction) actionsPerformed++
        return hasAction
    }
}
