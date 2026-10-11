package com.writinghelp.rewrite.bubble

import com.writinghelp.rewrite.R
import com.writinghelp.rewrite.data.Prompts
import com.writinghelp.rewrite.data.RewriteMode
import com.writinghelp.rewrite.data.SettingsRepository
import com.writinghelp.rewrite.network.ClaudeApiClient
import com.writinghelp.rewrite.network.ClaudeResult

/**
 * Rewrite logic + undo/redo/refine bookkeeping for the floating bubble. Mirrors what
 * [com.writinghelp.rewrite.ProcessTextViewModel] does for the popup, minus the ViewModel
 * base class. [BubbleService] owns one instance and drives it from its coroutine scope;
 * all node I/O (reading the field, writing text back) stays in the service /
 * [WritingAccessibilityService] — this class only decides *what* the field's text should become.
 */
class BubbleController(private val settings: SettingsRepository) {

    sealed class Outcome {
        /** Field text should be set to [newFullText] with the cursor at [cursorPos]. */
        data class Write(val newFullText: String, val cursorPos: Int, val statusRes: Int) : Outcome()
        /** Nothing to write; show [statusRes] (a hint or an error) in the strip. */
        data class Message(val statusRes: Int, val rawMessage: String? = null) : Outcome()
    }

    var mode: RewriteMode = RewriteMode.FIX_GRAMMAR
        private set

    /** True once a successful rewrite has been written and can be undone / redone / refined. */
    val hasActiveResult: Boolean
        get() = originalInput != null

    private var baseFullText: String = ""       // field contents before our first write (for Undo)
    private var postWriteFullText: String = ""  // field contents after our most recent write
    private var resultStart: Int = 0            // span in postWriteFullText our output occupies
    private var resultEnd: Int = 0
    private var originalInput: String? = null   // the text the user selected (Redo re-runs on this)
    private var lastResult: String = ""         // most recent Claude output (tone chips refine this)

    fun reset() {
        baseFullText = ""
        postWriteFullText = ""
        resultStart = 0
        resultEnd = 0
        originalInput = null
        lastResult = ""
    }

    /** First rewrite after the user picks a mode from the bubble menu. */
    suspend fun start(mode: RewriteMode, field: WritingAccessibilityService.FocusedField): Outcome {
        this.mode = mode
        val input = field.targetText
        if (input.isBlank()) return Outcome.Message(R.string.bubble_hint_no_text)

        val result = callClaude(Prompts.systemPromptFor(mode, settings.languages), input)
            ?: return authOutcome()
        return when (result) {
            is ClaudeResult.Success -> {
                baseFullText = field.fullText
                originalInput = input
                lastResult = result.text
                val spliced = BubbleTextSplice.splice(
                    field.fullText, field.replaceStart, field.replaceEnd, result.text
                )
                trackWrite(spliced, result.text)
                Outcome.Write(spliced.newText, spliced.newCursorPos, R.string.bubble_hint_done)
            }
            is ClaudeResult.Failure -> failureOutcome(result)
        }
    }

    /** Re-run the current mode on the originally selected text. */
    suspend fun redo(): Outcome {
        val input = originalInput ?: return Outcome.Message(R.string.bubble_hint_lost_field)
        val result = callClaude(Prompts.systemPromptFor(mode, settings.languages), input)
            ?: return authOutcome()
        return applyReplacement(result)
    }

    /** Tone chip: refine the last result with [instruction] (REWRITE only, matches the popup). */
    suspend fun refine(instruction: String): Outcome {
        if (originalInput == null) return Outcome.Message(R.string.bubble_hint_lost_field)
        val result = callClaude(Prompts.systemPromptFor(RewriteMode.REWRITE, settings.languages, instruction), lastResult)
            ?: return authOutcome()
        return applyReplacement(result)
    }

    /** Put the field back to how it was before our first write. */
    fun undo(): Outcome {
        if (originalInput == null) return Outcome.Message(R.string.bubble_hint_lost_field)
        val text = baseFullText
        reset()
        return Outcome.Write(text, text.length, R.string.bubble_hint_undone)
    }

    private fun applyReplacement(result: ClaudeResult): Outcome = when (result) {
        is ClaudeResult.Success -> {
            lastResult = result.text
            val spliced = BubbleTextSplice.splice(postWriteFullText, resultStart, resultEnd, result.text)
            trackWrite(spliced, result.text)
            Outcome.Write(spliced.newText, spliced.newCursorPos, R.string.bubble_hint_done)
        }
        is ClaudeResult.Failure -> failureOutcome(result)
    }

    private fun trackWrite(spliced: BubbleTextSplice.SpliceResult, output: String) {
        postWriteFullText = spliced.newText
        resultEnd = spliced.newCursorPos
        resultStart = spliced.newCursorPos - output.length
    }

    private suspend fun callClaude(systemPrompt: String, input: String): ClaudeResult? {
        val key = settings.apiKey
        if (key.isNullOrBlank()) return null
        return ClaudeApiClient(apiKey = key, model = settings.model).sendMessage(systemPrompt, input)
    }

    private fun authOutcome() = Outcome.Message(R.string.bubble_hint_no_key)

    private fun failureOutcome(f: ClaudeResult.Failure) =
        if (f.isAuthError) Outcome.Message(R.string.bubble_hint_no_key)
        else Outcome.Message(R.string.bubble_error, f.message)
}
