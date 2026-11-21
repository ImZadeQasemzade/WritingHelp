package com.writinghelp.rewrite.voice.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.writinghelp.rewrite.voice.core.Language

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    /** [alternates] excludes [text]; best first. */
    data class Final(val text: String, val alternates: List<String>) : SpeechEvent
    /** Shown briefly in the transcript strip. [fatal] = listening has stopped. */
    data class Error(val message: String, val fatal: Boolean) : SpeechEvent
    data class Listening(val active: Boolean) : SpeechEvent
    data class Level(val rmsDb: Float) : SpeechEvent
}

/**
 * Wraps [SpeechRecognizer] for continuous dictation: while the mic is "on", a new recognition
 * session starts automatically after each result or recoverable error. Must be used on the
 * main thread.
 */
class SpeechController(
    private val context: Context,
    private val onEvent: (SpeechEvent) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false
    private var consecutiveErrors = 0

    var language: Language = Language.EN
        private set

    val isListening: Boolean get() = wantListening

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (wantListening) return
        if (!isAvailable()) {
            onEvent(SpeechEvent.Error("Speech recognition isn't available on this device", fatal = true))
            return
        }
        wantListening = true
        consecutiveErrors = 0
        onEvent(SpeechEvent.Listening(true))
        startSession()
    }

    fun stop() {
        if (!wantListening) return
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        // stopListening() would still deliver the pending result; good for a manual stop.
        recognizer?.stopListening()
        onEvent(SpeechEvent.Listening(false))
    }

    fun toggle() = if (wantListening) stop() else start()

    fun setLanguage(lang: Language) {
        if (lang == language) return
        language = lang
        if (wantListening) restart(delayMs = 0)
    }

    fun destroy() {
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    // ------------------------------------------------------------------------------------

    private fun ensureRecognizer(): SpeechRecognizer =
        recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }

    private fun startSession() {
        if (!wantListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Hints; many engines ignore them.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }
        try {
            ensureRecognizer().startListening(intent)
        } catch (e: Exception) {
            onEvent(SpeechEvent.Error("Mic error: ${e.message}", fatal = false))
            restart(delayMs = 800, recreate = true)
        }
    }

    private fun restart(delayMs: Long, recreate: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        if (recreate) { recognizer?.destroy(); recognizer = null }
        handler.postDelayed({ startSession() }, delayMs)
    }

    private fun fatal(message: String) {
        wantListening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        onEvent(SpeechEvent.Error(message, fatal = true))
        onEvent(SpeechEvent.Listening(false))
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onEvent(SpeechEvent.Ready) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { onEvent(SpeechEvent.Level(rmsdB)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) onEvent(SpeechEvent.Partial(text))
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (list.isNotEmpty()) onEvent(SpeechEvent.Final(list.first(), list.drop(1).take(3)))
            if (wantListening) restart(delayMs = 50)
        }

        override fun onError(error: Int) {
            if (!wantListening) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(delayMs = 50) // silence: just keep going

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    fatal("Microphone permission needed — open WritingHelp")

                ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> fatal(
                    if (language == Language.FA)
                        "Persian (fa-IR) recognition isn't available on this device. Install Persian in Google voice typing settings."
                    else "${language.tag} recognition isn't available on this device"
                )

                else -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= 6) {
                        fatal(describe(error) + " — tap the mic to retry")
                        return
                    }
                    onEvent(SpeechEvent.Error(describe(error), fatal = false))
                    val busy = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == ERROR_CLIENT
                    restart(delayMs = (400L * consecutiveErrors).coerceAtMost(2000), recreate = busy)
                }
            }
        }
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network problem"
        SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> "Speech server error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
        ERROR_TOO_MANY_REQUESTS -> "Too many requests"
        else -> "Recognition error ($error)"
    }

    private companion object {
        // Constants added in API 31; inlined so the code compiles against any SDK and runs on 26+.
        const val ERROR_CLIENT = 5
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
