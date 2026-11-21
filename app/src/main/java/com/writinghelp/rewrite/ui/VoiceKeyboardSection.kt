package com.writinghelp.rewrite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.writinghelp.rewrite.data.SettingsRepository
import com.writinghelp.rewrite.voice.core.CommandGuide

/** Voice keyboard setup status, recomputed by [com.writinghelp.rewrite.MainActivity] on resume. */
data class VoiceUiState(
    val micGranted: Boolean = false,
    val keyboardEnabled: Boolean = false,
    val keyboardSelected: Boolean = false,
) {
    val ready: Boolean get() = micGranted && keyboardEnabled
}

/** Content of the "Voice Writing Keyboard" settings card. */
@Composable
fun VoiceKeyboardSection(
    settingsRepository: SettingsRepository,
    voice: VoiceUiState,
    onGrantMic: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onPickKeyboard: () -> Unit,
) {
    var smart by remember { mutableStateOf(settingsRepository.voiceSmartEdits) }
    var preview by remember { mutableStateOf(settingsRepository.voiceSmartPreview) }
    var showCommands by remember { mutableStateOf(false) }

    // --- Setup: three steps until done, then a single status line.
    if (voice.ready) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (voice.keyboardSelected) "✓  Ready and in use" else "✓  Ready",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onPickKeyboard) { Text("Switch keyboard") }
        }
    } else {
        SetupStep(1, "Allow the microphone", voice.micGranted, "Allow", onGrantMic)
        SetupStep(2, "Turn on Voice Writing Keyboard", voice.keyboardEnabled, "Open", onOpenKeyboardSettings)
        SetupStep(3, "Switch to it", voice.keyboardSelected, "Choose", onPickKeyboard)
    }

    Spacer(Modifier.height(8.dp))
    var test by remember { mutableStateOf("") }
    OutlinedTextField(
        value = test,
        onValueChange = { test = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        label = { Text("Try it here") },
    )

    // --- Commands
    Spacer(Modifier.height(12.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { showCommands = !showCommands }.padding(vertical = 6.dp),
    ) {
        Text("Voice commands", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (showCommands) "Hide ▲" else "Show ▼",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    Text(
        "Also on the keyboard: tap ? to see them while you talk.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (showCommands) CommandList()

    // --- Smart edits
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
    ToggleRow(
        title = "Smart voice edits",
        subtitle = "For edits the commands can't do, like \"make the last sentence more formal\". Sends up to " +
            "~2,000 characters around the cursor to Claude with your key and model above. Never in password " +
            "or incognito fields.",
        checked = smart,
    ) { smart = it; settingsRepository.voiceSmartEdits = it }
    if (smart) {
        ToggleRow(
            title = "Preview before applying",
            subtitle = "Show the proposed change with ✓ / ✗ first.",
            checked = preview,
        ) { preview = it; settingsRepository.voiceSmartPreview = it }
    }
    Text(
        "Persian needs Persian installed for Google voice typing. Long-press ? on the keyboard to open these settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CommandList() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for (entry in CommandGuide.commands) {
            CommandRow(entry.title, entry.en, entry.fa)
        }
        Spacer(Modifier.height(8.dp))
        Text("Smart voice edits (when on)", style = MaterialTheme.typography.labelLarge, color = muted)
        CommandRow(null, CommandGuide.smartExamples.first, CommandGuide.smartExamples.second)
        Spacer(Modifier.height(8.dp))
        Text("Modes", style = MaterialTheme.typography.labelLarge, color = muted)
        Text(CommandGuide.MODES, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Commands in either language work whichever language is selected.",
            style = MaterialTheme.typography.bodySmall, color = muted,
        )
    }
}

@Composable
private fun CommandRow(title: String?, en: List<String>, fa: List<String>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(en.joinToString("  ·  ") { "“$it”" }, style = MaterialTheme.typography.bodyMedium)
        Text(
            fa.joinToString("  ·  ") { "«$it»" },
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SetupStep(n: Int, title: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            if (done) "✓" else "$n",
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!done) OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
