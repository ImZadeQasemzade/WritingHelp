package com.writinghelp.rewrite.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Passive helper for the floating bubble. It does not act on accessibility events; the
 * [BubbleService] calls into [instance] on demand to:
 *
 *  - [captureFocusedField]: read the currently focused editable field's text + selection.
 *  - [writeText]: replace that field's whole contents (used for rewrite, redo, undo).
 *
 * Only ever reachable while the user has switched this service on in system Accessibility
 * settings; [instance] is null otherwise and the bubble degrades to a hint message.
 */
class WritingAccessibilityService : AccessibilityService() {

    data class FocusedField(
        val fullText: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val hadSelection: Boolean,
        val packageName: String?
    ) {
        /** The text the rewrite should run on: the selection, or the whole field. */
        val targetText: String
            get() = if (hadSelection) {
                fullText.substring(
                    selectionStart.coerceIn(0, fullText.length),
                    selectionEnd.coerceIn(0, fullText.length)
                )
            } else {
                fullText
            }

        /** Span in [fullText] the rewrite output should occupy (whole field when nothing was selected). */
        val replaceStart: Int get() = if (hadSelection) selectionStart else 0
        val replaceEnd: Int get() = if (hadSelection) selectionEnd else fullText.length
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* passive */ }

    override fun onInterrupt() { /* no-op */ }

    /** Reads the focused editable node, or null if there isn't one. */
    fun captureFocusedField(): FocusedField? {
        val node = findFocusedEditable() ?: return null
        val text = node.text?.toString() ?: ""
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        val hasSelection = start in 0..text.length && end in 0..text.length && start != end
        if (!hasSelection) {
            start = 0
            end = text.length
        }
        val pkg = node.packageName?.toString()
        node.recycleCompat()
        return FocusedField(
            fullText = text,
            selectionStart = start,
            selectionEnd = end,
            hadSelection = hasSelection,
            packageName = pkg
        )
    }

    /**
     * Replaces the focused field's entire text with [newFullText] and puts the cursor at
     * [cursorPos]. Tries ACTION_SET_TEXT first; falls back to a clipboard paste for fields
     * that reject direct text setting. Returns false if no editable field is focused.
     */
    fun writeText(newFullText: String, cursorPos: Int): Boolean {
        val node = findFocusedEditable() ?: return false
        val ok = trySetText(node, newFullText) || tryClipboardPaste(node, newFullText)
        if (ok) {
            val pos = cursorPos.coerceIn(0, newFullText.length)
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        node.recycleCompat()
        return ok
    }

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun tryClipboardPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val saved = clipboard.primaryClip
        clipboard.setPrimaryClip(ClipData.newPlainText("WritingHelp", text))

        val currentLen = node.text?.length ?: 0
        val selectAll = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentLen)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // Restore the user's clipboard shortly after the paste has been consumed.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (saved != null) clipboard.setPrimaryClip(saved)
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            } catch (_: Exception) { /* best effort */ }
        }, 400)

        return pasted
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val result = when {
            focused != null && focused.isEditable -> focused
            focused != null -> { focused.recycleCompat(); null }
            else -> null
        }
        if (result == null) root.recycleCompat()
        return result
    }

    private fun AccessibilityNodeInfo.recycleCompat() {
        @Suppress("DEPRECATION")
        try { recycle() } catch (_: Exception) { /* no-op on newer API levels */ }
    }

    companion object {
        @Volatile
        var instance: WritingAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
