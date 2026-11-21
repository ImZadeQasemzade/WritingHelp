package com.writinghelp.rewrite.voice.core

/**
 * The minimum the editor needs from a text field. The IME implements it over InputConnection;
 * tests implement it over a StringBuilder ([FakeTextField] in the test sources).
 *
 * Reads may return null: many apps implement InputConnection partially.
 */
interface TextField {
    fun textBeforeCursor(max: Int): String?
    fun textAfterCursor(max: Int): String?
    fun selectedText(): String?

    /**
     * Atomically delete [deleteBefore] chars before and [deleteAfter] chars after the cursor
     * (or the selection, which is replaced), then insert [insertBefore] + [insertAfter],
     * leaving the cursor between them.
     */
    fun replaceAroundCursor(deleteBefore: Int, deleteAfter: Int, insertBefore: String, insertAfter: String = "")

    /** Run the field's editor action (send/go/search/done). Returns false if it has none. */
    fun performEditorAction(): Boolean
}
