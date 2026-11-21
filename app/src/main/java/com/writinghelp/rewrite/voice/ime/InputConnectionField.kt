package com.writinghelp.rewrite.voice.ime

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.writinghelp.rewrite.voice.core.TextField

/** [TextField] over the current InputConnection. Every write is one batch edit. */
class InputConnectionField(
    private val ic: InputConnection,
    private val editorInfo: EditorInfo?,
) : TextField {

    override fun textBeforeCursor(max: Int): String? = ic.getTextBeforeCursor(max, 0)?.toString()

    override fun textAfterCursor(max: Int): String? = ic.getTextAfterCursor(max, 0)?.toString()

    override fun selectedText(): String? = ic.getSelectedText(0)?.toString()

    override fun replaceAroundCursor(deleteBefore: Int, deleteAfter: Int, insertBefore: String, insertAfter: String) {
        ic.beginBatchEdit()
        try {
            if (deleteBefore > 0 || deleteAfter > 0) ic.deleteSurroundingText(deleteBefore, deleteAfter)
            // commitText also replaces any active selection.
            if (insertBefore.isNotEmpty() || insertAfter.isEmpty()) ic.commitText(insertBefore, 1)
            // newCursorPosition 0 = cursor stays before this text.
            if (insertAfter.isNotEmpty()) ic.commitText(insertAfter, 0)
        } finally {
            ic.endBatchEdit()
        }
    }

    override fun performEditorAction(): Boolean {
        val info = editorInfo ?: return false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) return false
        return ic.performEditorAction(action)
    }
}
