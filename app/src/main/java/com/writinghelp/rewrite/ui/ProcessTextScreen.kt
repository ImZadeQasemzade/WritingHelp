package com.writinghelp.rewrite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.writinghelp.rewrite.ProcessTextViewModel
import com.writinghelp.rewrite.ScreenState
import com.writinghelp.rewrite.data.REFINEMENT_CHIPS
import com.writinghelp.rewrite.data.RewriteMode

@Composable
fun ProcessTextScreen(
    mode: RewriteMode,
    isReadOnly: Boolean,
    viewModel: ProcessTextViewModel,
    onReplace: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (mode == RewriteMode.FIX_GRAMMAR) "Fix Grammar" else "Rewrite with AI",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onCancel) {
                        Text("✕", style = MaterialTheme.typography.titleMedium)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (val state = viewModel.state) {
                    is ScreenState.Loading -> LoadingContent(mode)
                    is ScreenState.Result -> ResultContent(
                        mode = mode,
                        text = state.text,
                        isReadOnly = isReadOnly,
                        onRefine = viewModel::refine,
                        onReplace = { onReplace(state.text) },
                        onCopy = { onCopy(state.text) },
                        onCancel = onCancel
                    )
                    is ScreenState.Error -> ErrorContent(
                        message = state.message,
                        isAuthError = state.isAuthError,
                        onRetry = viewModel::retry,
                        onOpenSettings = onOpenSettings,
                        onCancel = onCancel
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingContent(mode: RewriteMode) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(mode.loadingLabel, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ResultContent(
    mode: RewriteMode,
    text: String,
    isReadOnly: Boolean,
    onRefine: (String) -> Unit,
    onReplace: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(12.dp)
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Clip
        )
    }

    if (mode.supportsRefinement) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            REFINEMENT_CHIPS.forEach { chip ->
                AssistChip(onClick = { onRefine(chip.instruction) }, label = { Text(chip.label) })
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = onCopy) { Text("Copy") }
        if (!isReadOnly) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onReplace) { Text("Replace") }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    isAuthError: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(modifier = Modifier.width(8.dp))
        if (isAuthError) {
            Button(onClick = onOpenSettings) { Text("Open Settings") }
        } else {
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
