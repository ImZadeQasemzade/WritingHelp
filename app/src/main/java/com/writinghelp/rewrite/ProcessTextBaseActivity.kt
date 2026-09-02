package com.writinghelp.rewrite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.writinghelp.rewrite.data.RewriteMode
import com.writinghelp.rewrite.data.SettingsRepository
import com.writinghelp.rewrite.ui.ProcessTextScreen
import com.writinghelp.rewrite.ui.theme.WritingHelpTheme

/**
 * Shared handler for the two ACTION_PROCESS_TEXT entry points ([FixGrammarActivity] and
 * [RewriteActivity]). Android launches one of these when the user taps our entry in the
 * text-selection popup that appears in any app (Messages, WhatsApp, Gmail, ...).
 *
 * See: https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT
 */
abstract class ProcessTextBaseActivity : ComponentActivity() {

    abstract val mode: RewriteMode

    private val selectedText: String by lazy {
        intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
    }

    /** false when the selection came from an editable field, meaning we can offer "Replace". */
    private val isReadOnly: Boolean by lazy {
        intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true) ?: true
    }

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    private val viewModel: ProcessTextViewModel by viewModels {
        ProcessTextViewModel.Factory(mode, selectedText, settingsRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (selectedText.isBlank()) {
            finish()
            return
        }

        setContent {
            WritingHelpTheme {
                ProcessTextScreen(
                    mode = mode,
                    isReadOnly = isReadOnly,
                    viewModel = viewModel,
                    onReplace = ::replaceAndFinish,
                    onCopy = ::copyToClipboard,
                    onOpenSettings = ::openSettings,
                    onCancel = { finish() }
                )
            }
        }
    }

    /** Returns the rewritten text to the app that invoked us; the OS replaces the selection with it. */
    private fun replaceAndFinish(text: String) {
        val result = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text as CharSequence)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WritingHelp result", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
