package com.writinghelp.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.writinghelp.rewrite.data.Prompts
import com.writinghelp.rewrite.data.RewriteMode
import com.writinghelp.rewrite.data.SettingsRepository
import com.writinghelp.rewrite.network.ClaudeApiClient
import com.writinghelp.rewrite.network.ClaudeResult
import kotlinx.coroutines.launch

sealed class ScreenState {
    data object Loading : ScreenState()
    data class Result(val text: String) : ScreenState()
    data class Error(val message: String, val isAuthError: Boolean) : ScreenState()
}

class ProcessTextViewModel(
    private val mode: RewriteMode,
    private val originalText: String,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    var state by mutableStateOf<ScreenState>(ScreenState.Loading)
        private set

    /** The most recent successful result, used as the input when the user taps a refinement chip. */
    var currentText by mutableStateOf(originalText)
        private set

    init {
        runRewrite(inputText = originalText, refinementInstruction = null)
    }

    fun retry() = runRewrite(inputText = originalText, refinementInstruction = null)

    fun refine(instruction: String) = runRewrite(inputText = currentText, refinementInstruction = instruction)

    private fun runRewrite(inputText: String, refinementInstruction: String?) {
        val apiKey = settingsRepository.apiKey
        if (apiKey.isNullOrBlank()) {
            state = ScreenState.Error(
                message = "No Claude API key set yet. Add one in WritingHelp settings.",
                isAuthError = true
            )
            return
        }

        state = ScreenState.Loading
        viewModelScope.launch {
            val client = ClaudeApiClient(apiKey = apiKey, model = settingsRepository.model)
            val systemPrompt = Prompts.systemPromptFor(mode, refinementInstruction)

            when (val result = client.sendMessage(systemPrompt, inputText)) {
                is ClaudeResult.Success -> {
                    currentText = result.text
                    state = ScreenState.Result(result.text)
                }
                is ClaudeResult.Failure -> {
                    state = ScreenState.Error(result.message, result.isAuthError)
                }
            }
        }
    }

    class Factory(
        private val mode: RewriteMode,
        private val originalText: String,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ProcessTextViewModel(mode, originalText, settingsRepository) as T
        }
    }
}
