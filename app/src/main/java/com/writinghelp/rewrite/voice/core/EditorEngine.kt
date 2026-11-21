package com.writinghelp.rewrite.voice.core

sealed interface EditResult {
    /** [label] is shown in the green chip, e.g. "Replaced". */
    data class Applied(val label: String) : EditResult
    data class Failed(val message: String) : EditResult
}

/**
 * Applies dictation and commands to a [TextField] and records every change in [history].
 * Pure Kotlin: the IME passes an InputConnection-backed field, tests pass a fake.
 */
class EditorEngine(
    val history: EditHistory = EditHistory(),
    private val window: Int = READ_WINDOW,
) {
    companion object {
        const val READ_WINDOW = 2000
        const val LABEL_DICTATION = "Dictated"
        const val LABEL_TYPED = "Typed"
        private val TIGHT_PUNCT = setOf(",", ".", "?", "!", "،", "؟", ";", ":", "؛")
        private val SENTENCE_BREAK = charArrayOf('.', '!', '?', '؟', '…', '\n')
        private val TRAILING_WORD = Regex("\\s*[^\\s]+\\s*$")
    }

    /** Text before the cursor; if the app won't tell us, fall back to what we last inserted. */
    private fun knownBefore(field: TextField): String? =
        field.textBeforeCursor(window) ?: history.last?.insertedBefore

    // ---------------------------------------------------------------- dictation

    fun dictate(field: TextField, phrase: String, language: Language): EditResult {
        val selected = field.selectedText().orEmpty()
        val before = if (selected.isEmpty()) knownBefore(field) else field.textBeforeCursor(window)
        val text = DictationFormatter.format(phrase, before, language)
        if (text.isEmpty()) return EditResult.Failed("Nothing to type")
        // With a selection, the app replaces it; we record the selection as "removed before"
        // so undo puts the old text back (as plain text, the selection itself is not restored).
        field.replaceAroundCursor(0, 0, text)
        history.record(Change(selected, "", text, "", LABEL_DICTATION))
        return EditResult.Applied(LABEL_DICTATION)
    }

    /** Swap the last dictated phrase for an alternate the recogniser offered. */
    fun replaceLastDictation(field: TextField, alternate: String, language: Language): EditResult {
        val last = history.last
        if (last == null || last.label != LABEL_DICTATION || last.insertedAfter.isNotEmpty()) {
            return EditResult.Failed("Nothing to replace")
        }
        val before = knownBefore(field) ?: return EditResult.Failed("Can't read this field")
        if (!before.endsWith(last.insertedBefore)) return EditResult.Failed("Text changed")
        val prefix = before.dropLast(last.insertedBefore.length)
        val text = DictationFormatter.format(alternate, prefix, language)
        if (text == last.insertedBefore) return EditResult.Applied("Replaced")
        return apply(field, Change(last.insertedBefore, "", text, "", LABEL_DICTATION), "Replaced")
    }

    // ---------------------------------------------------------------- commands

    fun undo(field: TextField): EditResult {
        val change = history.popUndo() ?: return EditResult.Failed("Nothing to undo")
        if (!matchesAroundCursor(field, change.insertedBefore, change.insertedAfter)) {
            history.restoreUndo(change)
            return EditResult.Failed("Text changed — can't undo")
        }
        replace(field, change.inverse())
        return EditResult.Applied("Undone")
    }

    fun redo(field: TextField): EditResult {
        val change = history.popRedo() ?: return EditResult.Failed("Nothing to redo")
        if (!matchesAroundCursor(field, change.removedBefore, change.removedAfter)) {
            history.restoreRedo(change)
            return EditResult.Failed("Text changed — can't redo")
        }
        replace(field, change)
        return EditResult.Applied("Redone")
    }

    fun deleteLastWord(field: TextField): EditResult {
        val before = knownBefore(field) ?: return EditResult.Failed("Can't read this field")
        val m = TRAILING_WORD.find(before) ?: return EditResult.Failed("No word to delete")
        return apply(field, Change(m.value, "", "", "", "Deleted word"), "Deleted word")
    }

    fun deleteLastSentence(field: TextField): EditResult {
        val before = knownBefore(field) ?: return EditResult.Failed("Can't read this field")
        val body = before.trimEnd()
        if (body.isEmpty()) return EditResult.Failed("No sentence to delete")
        // The sentence's own terminator is the last char of body; look for the previous one.
        val boundary = body.dropLast(1).lastIndexOfAny(SENTENCE_BREAK)
        val start = boundary + 1
        val removed = before.substring(start)
        return apply(field, Change(removed, "", "", "", "Deleted sentence"), "Deleted sentence")
    }

    /** Replace the occurrence of [old] closest before the cursor with [new]. */
    fun replace(field: TextField, old: String, new: String): EditResult {
        val before = knownBefore(field) ?: return EditResult.Failed("Can't read this field")
        val match = findLast(before, old) ?: return EditResult.Failed("\"${old.take(24)}\" not found")
        var replacement = new
        if (match.value.firstOrNull()?.isUpperCase() == true && replacement.firstOrNull()?.isLowerCase() == true) {
            replacement = replacement.replaceFirstChar { it.uppercaseChar() }
        }
        val tail = before.substring(match.range.last + 1)
        val change = Change(match.value + tail, "", replacement + tail, "", "Replaced")
        return apply(field, change, "Replaced")
    }

    /**
     * A tapped key (space or punctuation). Punctuation replaces one space right before it,
     * so "hello |" + "," gives "hello,". Each tap is its own undo step.
     */
    fun typeKey(field: TextField, key: String): EditResult {
        val before = field.textBeforeCursor(1)
        val change = if (key in TIGHT_PUNCT && before == " ") Change(" ", "", key, "", LABEL_TYPED)
        else Change("", "", key, "", LABEL_TYPED)
        return apply(field, change, LABEL_TYPED)
    }

    fun newLine(field: TextField, paragraph: Boolean): EditResult {
        val text = if (paragraph) "\n\n" else "\n"
        val label = if (paragraph) "New paragraph" else "New line"
        return apply(field, Change("", "", text, "", label), label)
    }

    fun send(field: TextField): EditResult =
        if (field.performEditorAction()) EditResult.Applied("Sent")
        else EditResult.Failed("This field can't send")

    // ---------------------------------------------------------------- smart (range) edits

    /**
     * Apply [edit] (offsets in [context].text) as one undo step. The field must still contain
     * exactly [context] around the cursor, otherwise nothing is changed.
     */
    fun applyRangeEdit(field: TextField, context: EditContext, edit: RangeEdit, label: String): EditResult {
        val full = context.text
        if (edit.start < 0 || edit.end < edit.start || edit.end > full.length) {
            return EditResult.Failed("Couldn't apply")
        }
        // Collapse a selection to its end (re-committing the same text) so the edit is cursor-anchored.
        val ctx = if (context.selected.isNotEmpty()) {
            if (field.selectedText() != context.selected) return EditResult.Failed("Text changed")
            field.replaceAroundCursor(0, 0, context.selected)
            EditContext(context.before + context.selected, context.after)
        } else context

        if (!matchesAroundCursor(field, ctx.before, ctx.after, strict = true)) {
            return EditResult.Failed("Text changed")
        }
        val cur = ctx.cursor
        val lo = minOf(edit.start, cur)
        val hi = maxOf(edit.end, cur)
        val region = full.substring(lo, edit.start) + edit.text + full.substring(edit.end, hi)
        val change = when {
            edit.end <= cur -> Change(full.substring(lo, cur), "", region, "", label)
            edit.start >= cur -> Change("", full.substring(cur, hi), "", region, label)
            else -> Change(full.substring(lo, cur), full.substring(cur, hi), region, "", label)
        }
        return apply(field, change, label)
    }

    // ---------------------------------------------------------------- helpers

    private fun apply(field: TextField, change: Change, label: String): EditResult {
        if (change.isNoOp) return EditResult.Failed("Nothing changed")
        replace(field, change)
        history.record(change)
        return EditResult.Applied(label)
    }

    private fun replace(field: TextField, c: Change) =
        field.replaceAroundCursor(c.removedBefore.length, c.removedAfter.length, c.insertedBefore, c.insertedAfter)

    /**
     * Check the field still holds [before]|[after] around the cursor. Unreadable fields pass
     * (we trust our own history) unless [strict].
     */
    private fun matchesAroundCursor(field: TextField, before: String, after: String, strict: Boolean = false): Boolean {
        if (before.isNotEmpty()) {
            val actual = field.textBeforeCursor(before.length)
            if (actual == null) { if (strict) return false } else if (actual != before) return false
        }
        if (after.isNotEmpty()) {
            val actual = field.textAfterCursor(after.length)
            if (actual == null) { if (strict) return false } else if (actual != after) return false
        }
        return true
    }

    /** Last occurrence of [needle] in [hay]: case-insensitive, space/ZWNJ-insensitive, whole words preferred. */
    private fun findLast(hay: String, needle: String): MatchResult? {
        val words = TextNormalizer.persianLetters(needle).trim().split(Regex("[\\s\\u200C]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val body = words.joinToString("[\\s\\u200C]+") { Regex.escape(it) }
        val source = TextNormalizer.persianLetters(hay) // same length: 1:1 char mapping
        val opts = setOf(RegexOption.IGNORE_CASE)
        val whole = Regex("(?<![\\p{L}\\p{N}])$body(?![\\p{L}\\p{N}])", opts).findAll(source).lastOrNull()
        val hit = whole ?: Regex(body, opts).findAll(source).lastOrNull() ?: return null
        // Return the match over the original text (persianLetters keeps indices aligned).
        return Regex(Regex.escape(hay.substring(hit.range))).find(hay, hit.range.first)
    }
}
