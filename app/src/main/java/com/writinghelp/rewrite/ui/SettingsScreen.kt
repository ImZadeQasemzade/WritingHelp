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
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.writinghelp.rewrite.data.CLAUDE_MODEL_OPTIONS
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsRepository: SettingsRepository) {
    var apiKey by remember { mutableStateOf(settingsRepository.apiKey.orEmpty()) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(settingsRepository.model) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    var savedNotice by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val selectedModelOption = CLAUDE_MODEL_OPTIONS.first { it.apiId == selectedModel }

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
                CLAUDE_MODEL_OPTIONS.forEach { option ->
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
    }
}
