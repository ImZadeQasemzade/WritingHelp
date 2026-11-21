package com.writinghelp.rewrite.voice.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.writinghelp.rewrite.voice.core.EditorEngine
import com.writinghelp.rewrite.voice.core.IntentClassifier
import com.writinghelp.rewrite.voice.core.RuleBasedClassifier
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.writinghelp.rewrite.MainActivity
import com.writinghelp.rewrite.data.SettingsRepository
import com.writinghelp.rewrite.voice.core.InputMode
import com.writinghelp.rewrite.voice.core.Language
import com.writinghelp.rewrite.voice.llm.ClaudeLlmClient
import com.writinghelp.rewrite.voice.llm.LlmCommandParser
import com.writinghelp.rewrite.voice.llm.SmartPipeline
import com.writinghelp.rewrite.voice.speech.SpeechController
import com.writinghelp.rewrite.voice.ui.KeyboardActions
import com.writinghelp.rewrite.voice.ui.KeyboardScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * The IME. Owns lifecycle and wiring; logic lives in [KeyboardController] / [EditorEngine].
 * Implements LifecycleOwner + SavedStateRegistryOwner so Compose can run inside an IME window.
 */
class VoiceKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val scope = MainScope()
    private lateinit var settings: SettingsRepository
    private lateinit var speech: SpeechController
    private lateinit var controller: KeyboardController
    private var rootView: View? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        settings = SettingsRepository(applicationContext)
        controller = KeyboardController(
            scope = scope,
            engine = EditorEngine(),
            classifier = buildClassifier(),
            field = { currentInputConnection?.let { InputConnectionField(it, currentInputEditorInfo) } },
            haptic = ::haptic,
            onModeChange = { settings.voiceMode = it.name },
        )
        val language = savedLanguage()
        controller.restore(language, savedMode())
        speech = SpeechController(this, controller::onSpeech)
        speech.setLanguage(language)
    }

    /** Rules first, then (optionally) the LLM parser, then dictation. */
    private fun buildClassifier(): IntentClassifier = SmartPipeline(
        rules = RuleBasedClassifier(),
        llm = ::llmParser,
        smartEnabled = { settings.voiceSmartEdits },
        isOnline = ::isOnline,
        onThinking = { controller.showPending("Thinking…") },
        onNotice = { controller.notify(it) },
    )

    private fun savedLanguage(): Language =
        runCatching { Language.valueOf(settings.voiceLanguage) }.getOrDefault(Language.EN)

    private fun savedMode(): InputMode =
        runCatching { InputMode.valueOf(settings.voiceMode) }.getOrDefault(InputMode.AUTO)

    private var cachedParser: Pair<String, LlmCommandParser>? = null

    /** Uses WritingHelp's API key and model. Rebuilt only when either changes. */
    private fun llmParser(): LlmCommandParser? {
        val key = settings.apiKey?.takeIf { it.isNotBlank() } ?: return null
        val model = settings.model
        val id = "${key.hashCode()}|$model"
        cachedParser?.let { (cachedId, parser) -> if (cachedId == id) return parser }
        return LlmCommandParser(ClaudeLlmClient(key, model)).also { cachedParser = id to it }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onCreateInputView(): View {
        // Compose looks for these owners on the window's decor view.
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VoiceKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@VoiceKeyboardService)
            setContent {
                val state by controller.state.collectAsState()
                KeyboardScreen(state, actions, bottomInsetPx = bottomInset.intValue)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            updateBottomInset(v, insets)
            insets
        }
        rootView = view
        return view
    }

    /** Space to leave under the keyboard for the system's ⌄ / 🌐 buttons (gesture navigation). */
    private val bottomInset = mutableIntStateOf(0)

    private fun updateBottomInset(v: View, given: WindowInsetsCompat? = null) {
        val insets = given ?: ViewCompat.getRootWindowInsets(v)
        val fromInsets = insets?.let {
            maxOf(
                it.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                it.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom,
            )
        } ?: 0
        bottomInset.intValue = if (fromInsets > 0) fromInsets else gestureImeBarHeight()
    }

    /**
     * Fallback when the window reports no inset: with gesture navigation (Android 10+) the
     * system draws its ⌄ / 🌐 buttons over the bottom of the keyboard window, in a bar of
     * navigation_bar_frame_height. Zero with 3-button navigation, where the keyboard sits above the bar.
     */
    private fun gestureImeBarHeight(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val modeId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        val gesture = modeId != 0 && resources.getInteger(modeId) == 2
        if (!gesture) return 0
        val id = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
            .takeIf { it != 0 } ?: resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private val actions = object : KeyboardActions {
        override fun onMic() = toggleMic()
        override fun onLanguage() {
            val lang = controller.state.value.language.toggle()
            settings.voiceLanguage = lang.name
            controller.setLanguage(lang)
            speech.setLanguage(lang)
        }
        override fun onMode(mode: InputMode) {
            controller.setMode(mode)
            settings.voiceMode = mode.name
        }
        override fun onQuick(action: QuickAction) = controller.onQuickAction(action)
        override fun onHelp() = controller.toggleHelp()
        override fun onKey(key: String) = controller.onKey(key)
        override fun onSuggestion(text: String) = controller.onSuggestion(text)
        override fun onPreviewAccept() = controller.acceptPreview()
        override fun onPreviewReject() = controller.rejectPreview()
        override fun onOpenSettings() = openSetup()
    }

    private fun toggleMic() {
        val s = controller.state.value
        if (!s.micEnabled) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            controller.notify("Microphone permission needed — opening setup")
            openSetup()
            return
        }
        speech.toggle()
    }

    private fun openSetup() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        rootView?.let { v -> v.post { updateBottomInset(v) } }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (restarting) return
        val password = isPassword(info)
        val incognito = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        if (password) speech.stop()
        controller.onNewField(
            micEnabled = !password,
            reason = if (password) "Mic is off in password fields" else null,
            sensitive = password || incognito,
        )
        controller.previewSmartEdits = settings.voiceSmartPreview
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Never keep the mic open while the keyboard is hidden.
        speech.stop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        speech.destroy()
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    /** Voice keyboard should always show its own full-height view, never fullscreen extract mode. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun haptic() {
        val v = rootView ?: return
        val type = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
        v.performHapticFeedback(type)
    }

    private fun isPassword(info: EditorInfo?): Boolean {
        if (info == null) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
