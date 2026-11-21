package com.writinghelp.rewrite.voice.ime

import com.writinghelp.rewrite.voice.core.InputMode
import com.writinghelp.rewrite.voice.core.Language
import com.writinghelp.rewrite.voice.core.WordDiff

enum class ChipKind { SUCCESS, ERROR, PENDING }

data class StatusChip(val text: String, val kind: ChipKind)

/** A smart edit waiting for Apply / Discard (preview mode), as a word diff with some context. */
data class PendingPreview(val summary: String, val segments: List<WordDiff.Segment>)

data class KeyboardUiState(
    val language: Language = Language.EN,
    val mode: InputMode = InputMode.AUTO,
    val listening: Boolean = false,
    /** 0..1, smoothed mic level for the pulse. */
    val level: Float = 0f,
    val partial: String = "",
    val lastFinal: String = "",
    /** Short informational / error text shown in the strip instead of the transcript. */
    val message: String? = null,
    val chip: StatusChip? = null,
    val suggestions: List<String> = emptyList(),
    val micEnabled: Boolean = true,
    val micDisabledReason: String? = null,
    val preview: PendingPreview? = null,
    /** The commands cheat sheet is open in place of the controls. */
    val showHelp: Boolean = false,
)

enum class QuickAction { UNDO, REDO, DELETE_WORD, DELETE_SENTENCE, NEW_LINE }
