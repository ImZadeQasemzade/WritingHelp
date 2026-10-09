package com.writinghelp.rewrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.writinghelp.rewrite.R
import com.writinghelp.rewrite.data.ClaudeModelOption
import com.writinghelp.rewrite.data.FALLBACK_MODEL_OPTIONS
import com.writinghelp.rewrite.data.defaultModelFrom
import com.writinghelp.rewrite.data.descriptionFor
import com.writinghelp.rewrite.data.SettingsRepository
import com.writinghelp.rewrite.network.ClaudeApiClient
import com.writinghelp.rewrite.network.ClaudeResult
import kotlinx.coroutines.launch

private sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data class Success(val reply: String) : TestState()
    data class Failed(val message: String) : TestState()
}

/** Snapshot of the floating-bubble feature state, recomputed by [com.writinghelp.rewrite.MainActivity]. */
data class BubbleUiState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val canOverlay: Boolean = false,
    val accessibilityOn: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    bubble: BubbleUiState = BubbleUiState(),
    onBubbleToggle: (Boolean) -> Unit = {},
    onGrantOverlay: () -> Unit = {},
    onGrantAccessibility: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(settingsRepository.apiKey.orEmpty()) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(settingsRepository.model) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    var savedNotice by remember { mutableStateOf(false) }
    var savedApiKey by remember { mutableStateOf(settingsRepository.apiKey) }
    var modelOptions by remember { mutableStateOf(FALLBACK_MODEL_OPTIONS) }
    var modelsLive by remember { mutableStateOf(false) }

    // Fetch the latest models each time Settings opens (and again after a new key is saved).
    // If the saved model isn't offered any more, switch to the newest Sonnet.
    LaunchedEffect(savedApiKey) {
        val key = savedApiKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val live = ClaudeApiClient.listModels(key) ?: return@LaunchedEffect
        modelOptions = live
        modelsLive = true
        if (live.none { it.apiId == selectedModel }) {
            selectedModel = defaultModelFrom(live)
            settingsRepository.model = selectedModel
        }
    }

    val scope = rememberCoroutineScope()
    val selectedModelOption = modelOptions.firstOrNull { it.apiId == selectedModel }
        ?: ClaudeModelOption(selectedModel, selectedModel, descriptionFor(selectedModel))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("WritingHelp", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Select text in any app and you'll see \"Fix Grammar\" and \"Rewrite with AI\" " +
                "next to Copy/Paste. On some phones these show under a \"...\" more-options entry " +
                "in the selection menu instead of inline - that's controlled by the keyboard/OS, not this app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
        Text("Claude API key", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Stored encrypted on this device only, and used to call the Claude API directly " +
                "from the app. Get a key at console.anthropic.com.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; savedNotice = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Text(if (apiKeyVisible) "Hide" else "Show")
                }
            }
        )

        Spacer(Modifier.height(20.dp))
        Text("Model", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (modelsLive) "Latest models available to your key."
            else "Built-in list. Save an API key to load the latest models.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = { modelMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedModelOption.displayName,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text("Model") },
                supportingText = { Text(selectedModelOption.description) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) }
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false }
            ) {
                modelOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        onClick = {
                            selectedModel = option.apiId
                            modelMenuExpanded = false
                            savedNotice = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    settingsRepository.apiKey = apiKey.trim().ifBlank { null }
                    settingsRepository.model = selectedModel
                    savedApiKey = settingsRepository.apiKey
                    savedNotice = true
                    testState = TestState.Idle
                },
                modifier = Modifier.weight(1f)
            ) { Text("Save") }

            OutlinedButton(
                enabled = apiKey.isNotBlank() && testState !is TestState.Testing,
                modifier = Modifier.weight(1f),
                onClick = {
                    testState = TestState.Testing
                    scope.launch {
                        val client = ClaudeApiClient(apiKey.trim(), selectedModel)
                        when (val result = client.sendMessage(
                            systemPrompt = "Reply with exactly one word: OK.",
                            userText = "Ping"
                        )) {
                            is ClaudeResult.Success -> testState = TestState.Success(result.text)
                            is ClaudeResult.Failure -> testState = TestState.Failed(result.message)
                        }
                    }
                }
            ) { Text("Test connection") }
        }

        if (savedNotice) {
            Spacer(Modifier.height(12.dp))
            Text("Saved.", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(12.dp))
        when (val state = testState) {
            is TestState.Idle -> {}
            is TestState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Testing…")
            }
            is TestState.Success -> Card {
                Text(
                    "Connected - Claude replied: \"${state.reply}\"",
                    modifier = Modifier.padding(12.dp)
                )
            }
            is TestState.Failed -> Text(
                "Failed: ${state.message}",
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.bubble_section_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.bubble_section_blurb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bubble_switch_label), modifier = Modifier.weight(1f))
            Switch(checked = bubble.running, onCheckedChange = onBubbleToggle)
        }

        val statusRes = when {
            bubble.running -> R.string.bubble_status_on
            bubble.enabled && !bubble.canOverlay -> R.string.bubble_status_needs_overlay
            bubble.enabled && !bubble.accessibilityOn -> R.string.bubble_status_needs_accessibility
            else -> R.string.bubble_status_off
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(statusRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (bubble.enabled && !bubble.canOverlay) {
            TextButton(onClick = onGrantOverlay, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(R.string.bubble_grant_overlay))
            }
        }
        if (bubble.enabled && bubble.canOverlay && !bubble.accessibilityOn) {
            TextButton(onClick = onGrantAccessibility, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(R.string.bubble_grant_accessibility))
            }
        }
    }
}
