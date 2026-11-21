package com.writinghelp.rewrite.voice.core

/**
 * One change the keyboard made, expressed relative to the cursor so it can be reverted in any
 * app — no absolute offsets, no reliance on the app's own undo.
 *
 * Before the change, the text around the cursor was  … [removedBefore] | [removedAfter] …
 * After it, it is                                      … [insertedBefore] | [insertedAfter] …
 */
data class Change(
    val removedBefore: String,
    val removedAfter: String,
    val insertedBefore: String,
    val insertedAfter: String,
    val label: String,
) {
    fun inverse(): Change = Change(insertedBefore, insertedAfter, removedBefore, removedAfter, label)
    val isNoOp: Boolean get() = removedBefore == insertedBefore && removedAfter == insertedAfter
}

/** Undo/redo stacks of every change the keyboard makes, independent of the host app. */
class EditHistory(private val limit: Int = 200) {
    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val last: Change? get() = undoStack.lastOrNull()

    fun record(change: Change) {
        if (change.isNoOp) return
        undoStack.addLast(change)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun popUndo(): Change? = undoStack.removeLastOrNull()?.also { redoStack.addLast(it) }
    fun popRedo(): Change? = redoStack.removeLastOrNull()?.also { undoStack.addLast(it) }

    /** Put back a change whose undo/redo couldn't be applied. */
    fun restoreUndo(change: Change) { redoStack.removeLastOrNull(); undoStack.addLast(change) }
    fun restoreRedo(change: Change) { undoStack.removeLastOrNull(); redoStack.addLast(change) }

    fun clear() { undoStack.clear(); redoStack.clear() }
}
