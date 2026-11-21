package com.writinghelp.rewrite.voice.ime

import com.writinghelp.rewrite.voice.core.ClassifyRequest
import com.writinghelp.rewrite.voice.core.CommandType
import com.writinghelp.rewrite.voice.core.EditContext
import com.writinghelp.rewrite.voice.core.EditResult
import com.writinghelp.rewrite.voice.core.EditorEngine
import com.writinghelp.rewrite.voice.core.InputMode
import com.writinghelp.rewrite.voice.core.IntentClassifier
import com.writinghelp.rewrite.voice.core.KeyboardIntent
import com.writinghelp.rewrite.voice.core.Language
import com.writinghelp.rewrite.voice.core.TextField
import com.writinghelp.rewrite.voice.core.WordDiff
import com.writinghelp.rewrite.voice.speech.SpeechEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keyboard logic between speech, classifier and editor. No Android UI types — the service
 * supplies the current field and side effects (haptics, keyboard switching).
 */
class KeyboardController(
    private val scope: CoroutineScope,
    private val engine: EditorEngine,
    private val classifier: IntentClassifier,
    private val field: () -> TextField?,
    private val haptic: () -> Unit,
    /** Persists a mode chosen by voice. */
    private val onModeChange: (InputMode) -> Unit = {},
) {
    private val _state = MutableStateFlow(KeyboardUiState())
    val state: StateFlow<KeyboardUiState> = _state

    /** Finals are processed strictly in order, even if classification is async (LLM). */
    private val pipeline = Mutex()
    private var chipJob: Job? = null
    private var messageJob: Job? = null
    private var pendingApply: (() -> Unit)? = null

    /** False in password / incognito fields: their text is never sent to a smart classifier. */
    var allowContextForSmartEdits: Boolean = true

    // ------------------------------------------------------------------ setup

    fun restore(language: Language, mode: InputMode) =
        _state.update { it.copy(language = language, mode = mode) }

    fun onNewField(micEnabled: Boolean, reason: String?, sensitive: Boolean) {
        engine.history.clear()
        allowContextForSmartEdits = !sensitive
        pendingApply = null
        _state.update {
            it.copy(
                micEnabled = micEnabled, micDisabledReason = reason, suggestions = emptyList(),
                partial = "", lastFinal = "", preview = null, chip = null, showHelp = false,
                message = reason,
            )
        }
    }

    fun toggleHelp() = _state.update { it.copy(showHelp = !it.showHelp) }

    fun setLanguage(lang: Language) = _state.update { it.copy(language = lang) }
    fun setMode(mode: InputMode) = _state.update { it.copy(mode = mode) }

    // ------------------------------------------------------------------ speech

    fun onSpeech(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Listening -> _state.update {
                it.copy(listening = event.active, level = 0f, partial = if (event.active) it.partial else "")
            }
            is SpeechEvent.Ready -> clearMessage()
            is SpeechEvent.Level -> _state.update {
                // RMS dB is roughly -2..10; map to 0..1 and smooth.
                val target = ((event.rmsDb + 2f) / 12f).coerceIn(0f, 1f)
                it.copy(level = it.level * 0.6f + target * 0.4f)
            }
            is SpeechEvent.Partial -> _state.update { it.copy(partial = event.text, message = null) }
            is SpeechEvent.Final -> {
                _state.update { it.copy(partial = "", lastFinal = event.text) }
                scope.launch { pipeline.withLock { handleFinal(event.text, event.alternates) } }
            }
            is SpeechEvent.Error -> showMessage(event.message, sticky = event.fatal)
        }
    }

    private suspend fun handleFinal(text: String, alternates: List<String>) {
        val f = field() ?: return
        val s = _state.value
        val request = ClassifyRequest(
            phrase = text, language = s.language, mode = s.mode,
            context = if (allowContextForSmartEdits) readContext(f) else null,
        )
        val intent = classifier.classify(request)
        if (_state.value.chip?.kind == ChipKind.PENDING) _state.update { it.copy(chip = null) }
        // The field may have changed while we were classifying.
        val target = field() ?: return
        when (intent) {
            is KeyboardIntent.Dictation -> {
                val r = engine.dictate(target, intent.text, s.language)
                if (r is EditResult.Applied) {
                    _state.update { it.copy(suggestions = alternates.filter { a -> !a.equals(intent.text, true) }) }
                } else report(r)
            }
            is KeyboardIntent.Command -> {
                _state.update { it.copy(suggestions = emptyList()) }
                report(runCommand(target, intent))
            }
            is KeyboardIntent.SmartEdit -> {
                _state.update { it.copy(suggestions = emptyList()) }
                applySmartEdit(target, intent)
            }
            is KeyboardIntent.Rejected -> showChip(intent.message, ChipKind.ERROR)
        }
    }

    private fun readContext(f: TextField): EditContext? {
        val before = f.textBeforeCursor(EditorEngine.READ_WINDOW) ?: return null
        val after = f.textAfterCursor(EditorEngine.READ_WINDOW).orEmpty()
        val selected = f.selectedText().orEmpty()
        // Cap the whole context at ~2,000 chars, centred on the cursor.
        val budget = EditorEngine.READ_WINDOW - selected.length
        val keepAfter = minOf(after.length, maxOf(budget / 4, budget - before.length))
        val keepBefore = minOf(before.length, budget - keepAfter)
        return EditContext(before.takeLast(keepBefore), after.take(keepAfter), selected)
    }

    private fun runCommand(f: TextField, cmd: KeyboardIntent.Command): EditResult = when (cmd.type) {
        CommandType.UNDO -> engine.undo(f)
        CommandType.REDO -> engine.redo(f)
        CommandType.DELETE_WORD -> engine.deleteLastWord(f)
        CommandType.DELETE_SENTENCE -> engine.deleteLastSentence(f)
        CommandType.REPLACE -> engine.replace(f, cmd.args[0], cmd.args[1])
        CommandType.NEW_LINE -> engine.newLine(f, paragraph = false)
        CommandType.NEW_PARAGRAPH -> engine.newLine(f, paragraph = true)
        CommandType.SEND -> engine.send(f)
        CommandType.MODE_AUTO, CommandType.MODE_DICTATE, CommandType.MODE_COMMAND -> {
            val mode = cmd.type.switchesTo!!
            setMode(mode)
            onModeChange(mode)
            EditResult.Applied(
                when (mode) {
                    InputMode.AUTO -> "Auto mode"
                    InputMode.DICTATE -> "Dictate mode"
                    InputMode.COMMAND -> "Command mode"
                }
            )
        }
    }

    // ------------------------------------------------------------------ smart edits

    /** Set by the service from settings. */
    var previewSmartEdits: Boolean = false

    private fun applySmartEdit(f: TextField, intent: KeyboardIntent.SmartEdit) {
        val apply = { report(engine.applyRangeEdit(f, intent.context, intent.edit, intent.summary)) }
        if (!previewSmartEdits) { apply(); return }
        pendingApply = apply
        _state.update { it.copy(preview = PendingPreview(intent.summary, previewSegments(intent))) }
    }

    /** Word diff of the edited range, with a little unchanged text on each side for orientation. */
    private fun previewSegments(intent: KeyboardIntent.SmartEdit): List<WordDiff.Segment> {
        val full = intent.context.text
        val (start, end) = intent.edit.start to intent.edit.end
        val lead = full.substring(0, start).let { s ->
            if (s.length <= CONTEXT_CHARS) s
            else "…" + s.takeLast(CONTEXT_CHARS).substringAfter(' ', s.takeLast(CONTEXT_CHARS))
        }
        val tail = full.substring(end).let { s ->
            if (s.length <= CONTEXT_CHARS) s
            else s.take(CONTEXT_CHARS).substringBeforeLast(' ', s.take(CONTEXT_CHARS)) + "…"
        }
        return buildList {
            if (lead.isNotEmpty()) add(WordDiff.Segment(lead, WordDiff.Kind.SAME))
            addAll(WordDiff.diff(full.substring(start, end), intent.edit.text))
            if (tail.isNotEmpty()) add(WordDiff.Segment(tail, WordDiff.Kind.SAME))
        }
    }

    private companion object {
        const val CONTEXT_CHARS = 60
    }

    fun acceptPreview() {
        val a = pendingApply
        pendingApply = null
        _state.update { it.copy(preview = null) }
        a?.invoke()
    }

    fun rejectPreview() {
        pendingApply = null
        _state.update { it.copy(preview = null) }
        showChip("Discarded", ChipKind.ERROR)
    }

    /** Lets a slow classifier show a spinner chip. */
    fun showPending(text: String) = showChip(text, ChipKind.PENDING, durationMs = 0)
    fun notify(text: String) = showMessage(text, sticky = false)

    // ------------------------------------------------------------------ buttons

    fun onQuickAction(action: QuickAction) {
        val f = field() ?: return
        scope.launch {
            pipeline.withLock {
                val r = when (action) {
                    QuickAction.UNDO -> engine.undo(f)
                    QuickAction.REDO -> engine.redo(f)
                    QuickAction.DELETE_WORD -> engine.deleteLastWord(f)
                    QuickAction.DELETE_SENTENCE -> engine.deleteLastSentence(f)
                    QuickAction.NEW_LINE -> engine.newLine(f, paragraph = false)
                }
                _state.update { it.copy(suggestions = emptyList()) }
                report(r)
            }
        }
    }

    /** Space / punctuation keys. */
    fun onKey(key: String) {
        val f = field() ?: return
        scope.launch {
            pipeline.withLock {
                _state.update { it.copy(suggestions = emptyList()) }
                report(engine.typeKey(f, key))
            }
        }
    }

    fun onSuggestion(alternate: String) {
        val f = field() ?: return
        scope.launch {
            pipeline.withLock {
                val old = _state.value.lastFinal
                val r = engine.replaceLastDictation(f, alternate, _state.value.language)
                if (r is EditResult.Applied) {
                    _state.update {
                        it.copy(
                            lastFinal = alternate,
                            suggestions = (it.suggestions - alternate + old).filter { s -> s.isNotBlank() },
                        )
                    }
                }
                report(r)
            }
        }
    }

    // ------------------------------------------------------------------ feedback

    private fun report(r: EditResult) {
        when (r) {
            is EditResult.Applied -> {
                if (r.label == EditorEngine.LABEL_DICTATION || r.label == EditorEngine.LABEL_TYPED) return
                haptic()
                val icon = when (r.label) { "Undone" -> "⟲"; "Redone" -> "⟳"; else -> "✓" }
                showChip("$icon ${r.label}", ChipKind.SUCCESS)
            }
            is EditResult.Failed -> showChip(r.message, ChipKind.ERROR)
        }
    }

    private fun showChip(text: String, kind: ChipKind, durationMs: Long = 2000) {
        chipJob?.cancel()
        _state.update { it.copy(chip = StatusChip(text, kind)) }
        if (durationMs > 0) chipJob = scope.launch {
            delay(durationMs)
            _state.update { it.copy(chip = null) }
        }
    }

    private fun showMessage(text: String, sticky: Boolean) {
        messageJob?.cancel()
        _state.update { it.copy(message = text) }
        if (!sticky) messageJob = scope.launch {
            delay(2500)
            _state.update { if (it.message == text) it.copy(message = null) else it }
        }
    }

    private fun clearMessage() {
        if (_state.value.micEnabled) _state.update { it.copy(message = null) }
    }
}
