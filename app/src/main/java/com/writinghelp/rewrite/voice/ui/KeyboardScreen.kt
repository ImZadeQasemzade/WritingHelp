package com.writinghelp.rewrite.voice.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.remember
import com.writinghelp.rewrite.voice.core.WordDiff
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.writinghelp.rewrite.voice.core.CommandGuide
import com.writinghelp.rewrite.voice.core.CommandType
import com.writinghelp.rewrite.voice.core.InputMode
import com.writinghelp.rewrite.voice.core.Language
import com.writinghelp.rewrite.voice.ime.ChipKind
import com.writinghelp.rewrite.voice.ime.KeyboardUiState
import com.writinghelp.rewrite.voice.ime.QuickAction

interface KeyboardActions {
    fun onMic()
    fun onLanguage()
    fun onMode(mode: InputMode)
    fun onQuick(action: QuickAction)
    fun onSuggestion(text: String)
    fun onPreviewAccept()
    fun onPreviewReject()
    fun onOpenSettings()
    fun onHelp()
    fun onKey(key: String)
}

private object Palette {
    val bg = Color(0xFF121317)
    val surface = Color(0xFF1E2026)
    val surfaceHi = Color(0xFF2A2D35)
    val text = Color(0xFFF2F3F5)
    val muted = Color(0xFF8A8F99)
    val accent = Color(0xFF5B8CFF)
    val listening = Color(0xFFFF4D5E)
    val success = Color(0xFF2DBE6C)
    val error = Color(0xFFE5A03A)
    val disabled = Color(0xFF3A3D44)
    val removed = Color(0xFFFF7B85)
    val added = Color(0xFF6EE7A0)
}

@Composable
fun KeyboardScreen(state: KeyboardUiState, actions: KeyboardActions, bottomInsetPx: Int = 0) {
    val dir = if (state.language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val bottom = with(LocalDensity.current) { bottomInsetPx.toDp() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.bg)
            // Keep clear of the system's ⌄ / 🌐 buttons, like a regular keyboard.
            .padding(bottom = bottom)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val preview = state.preview
        if (preview != null) {
            // Review a smart edit: the whole keyboard body becomes the before/after view.
            PreviewBar(preview.summary, actions)
            Spacer(Modifier.height(6.dp))
            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                PreviewPanel(preview.segments)
            }
            return@Column
        }
        CompositionLocalProvider(LocalLayoutDirection provides dir) {
            TranscriptStrip(state, actions)
            Spacer(Modifier.height(6.dp))
            if (state.showHelp) {
                HelpPanel(state.language, actions)
            } else {
                SuggestionRow(state.suggestions, actions)
            }
        }
        if (!state.showHelp) {
            Spacer(Modifier.height(4.dp))
            ControlRow(state, actions)
        }
        Spacer(Modifier.height(6.dp))
        QuickEditBar(state.showHelp, actions)
        Spacer(Modifier.height(6.dp))
        PunctuationRow(state.language, actions)
    }
}

// ---------------------------------------------------------------- 5. space + punctuation

/** , . ? [space] ! — Persian comma and question mark when FA is selected. */
@Composable
private fun PunctuationRow(language: Language, actions: KeyboardActions) {
    val fa = language == Language.FA
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TypeKey(if (fa) "،" else ",", 1f, actions)
        TypeKey(".", 1f, actions)
        TypeKey(if (fa) "؟" else "?", 1f, actions)
        TypeKey(" ", 4f, actions, label = if (fa) "فاصله" else "space")
        TypeKey("!", 1f, actions)
    }
}

@Composable
private fun RowScope.TypeKey(key: String, weight: Float, actions: KeyboardActions, label: String = key) {
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.surfaceHi)
            .clickable { actions.onKey(key) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Palette.text,
            fontSize = if (label.length > 1) 14.sp else 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------- smart edit preview

@Composable
private fun PreviewBar(summary: String, actions: KeyboardActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.surface)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(summary, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("Review the change", color = Palette.muted, fontSize = 11.sp, maxLines = 1)
        }
        PillButton("Discard", Palette.surfaceHi, actions::onPreviewReject)
        Spacer(Modifier.width(6.dp))
        PillButton("Apply", Palette.success, actions::onPreviewAccept)
    }
}

@Composable
private fun PillButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** Scrollable word diff: removed words struck through in red, new words in green. */
@Composable
private fun PreviewPanel(segments: List<WordDiff.Segment>) {
    val removedBg = Palette.removed.copy(alpha = 0.22f)
    val addedBg = Palette.success.copy(alpha = 0.22f)
    val text = remember(segments) {
        buildAnnotatedString {
            for (s in segments) {
                when (s.kind) {
                    WordDiff.Kind.SAME -> withStyle(SpanStyle(color = Palette.muted)) { append(s.text) }
                    WordDiff.Kind.REMOVED -> withStyle(
                        SpanStyle(
                            color = Palette.removed, background = removedBg,
                            textDecoration = TextDecoration.LineThrough,
                        )
                    ) { append(s.text) }
                    WordDiff.Kind.ADDED -> withStyle(
                        SpanStyle(color = Palette.added, background = addedBg, fontWeight = FontWeight.Medium)
                    ) { append(s.text) }
                }
            }
        }
    }
    // Takes the space of suggestions + controls + quick bar + punctuation row (152 + 6 + 52 + 6 + 40 dp).
    Box(
        Modifier
            .fillMaxWidth()
            .height(256.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            style = TextStyle(textDirection = TextDirection.Content),
        )
    }
}

// ---------------------------------------------------------------- commands cheat sheet

@Composable
private fun HelpPanel(language: Language, actions: KeyboardActions) {
    val fa = language == Language.FA
    // Same height as suggestions + controls, so the keyboard doesn't jump.
    Column(
        Modifier
            .fillMaxWidth()
            .height(152.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        for (entry in CommandGuide.commands) {
            val examples = if (fa) entry.fa else entry.en
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    if (fa) faTitle(entry.type) else entry.title,
                    color = Palette.muted, fontSize = 12.sp, modifier = Modifier.width(118.dp),
                )
                Text(
                    examples.joinToString("  ·  ") { "“$it”" },
                    color = Palette.text, fontSize = 14.sp,
                    style = TextStyle(textDirection = TextDirection.Content),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (fa) "ویرایش هوشمند (اگر روشن باشد):" else "Smart edits (if on in settings):",
            color = Palette.muted, fontSize = 12.sp,
        )
        val smart = if (fa) CommandGuide.smartExamples.second else CommandGuide.smartExamples.first
        Text(
            smart.joinToString("  ·  ") { "“$it”" },
            color = Palette.text, fontSize = 14.sp,
            style = TextStyle(textDirection = TextDirection.Content),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (fa) "دستورهای انگلیسی هم کار می‌کنند. نگه‌داشتن ? تنظیمات را باز می‌کند."
            else "Persian commands work in EN too. Long-press ? for settings.",
            color = Palette.muted, fontSize = 12.sp,
        )
    }
}

private fun faTitle(type: CommandType): String = when (type) {
    CommandType.UNDO -> "برگرداندن"
    CommandType.REDO -> "انجام دوباره"
    CommandType.DELETE_WORD -> "پاک کردن کلمه"
    CommandType.DELETE_SENTENCE -> "پاک کردن جمله"
    CommandType.REPLACE -> "جایگزینی"
    CommandType.NEW_LINE -> "خط جدید"
    CommandType.NEW_PARAGRAPH -> "پاراگراف جدید"
    CommandType.SEND -> "ارسال"
    CommandType.MODE_AUTO -> "حالت خودکار"
    CommandType.MODE_DICTATE -> "حالت دیکته"
    CommandType.MODE_COMMAND -> "حالت دستور"
}

// ---------------------------------------------------------------- 1. transcript strip

@Composable
private fun TranscriptStrip(state: KeyboardUiState, actions: KeyboardActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (text, color) = when {
            state.message != null -> state.message to Palette.error
            state.partial.isNotEmpty() -> state.partial to Palette.muted
            state.lastFinal.isNotEmpty() -> state.lastFinal to Palette.text
            state.listening -> (if (state.language == Language.FA) "بگویید…" else "Speak now…") to Palette.muted
            else -> (if (state.language == Language.FA) "برای شروع میکروفون را بزنید" else "Tap the mic to start") to Palette.muted
        }
        Text(
            text = text,
            color = color,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Content direction: mixed Persian/English text lays out by its first strong character.
            style = TextStyle(textDirection = TextDirection.Content),
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(visible = state.chip != null, enter = fadeIn(), exit = fadeOut()) {
            state.chip?.let { Chip(it.text, it.kind) }
        }
    }
}

@Composable
private fun Chip(text: String, kind: ChipKind) {
    val bg = when (kind) {
        ChipKind.SUCCESS -> Palette.success
        ChipKind.ERROR -> Palette.error
        ChipKind.PENDING -> Palette.surfaceHi
    }
    Row(
        Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == ChipKind.PENDING) {
            CircularProgressIndicator(Modifier.size(12.dp), color = Palette.text, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RoundButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

// ---------------------------------------------------------------- 2. suggestions

@Composable
private fun SuggestionRow(suggestions: List<String>, actions: KeyboardActions) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in suggestions) {
            Text(
                s,
                color = Palette.text,
                fontSize = 14.sp,
                maxLines = 1,
                style = TextStyle(textDirection = TextDirection.Content),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, Palette.surfaceHi, RoundedCornerShape(50))
                    .clickable { actions.onSuggestion(s) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 3. controls

@Composable
private fun ControlRow(state: KeyboardUiState, actions: KeyboardActions) {
    Row(
        Modifier.fillMaxWidth().height(112.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideButton(
            top = state.language.label,
            bottom = if (state.language == Language.FA) "فارسی" else "English",
            onClick = actions::onLanguage,
        )
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            MicButton(state, actions)
        }
        ModeColumn(state.mode, actions)
    }
}

/** Auto / Dictate / Command as three stacked buttons; the selected one is filled. */
@Composable
private fun ModeColumn(selected: InputMode, actions: KeyboardActions) {
    Column(Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for ((mode, label) in listOf(
            InputMode.AUTO to "Auto",
            InputMode.DICTATE to "Dictate",
            InputMode.COMMAND to "Command",
        )) {
            val on = mode == selected
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(31.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Palette.accent else Palette.surface)
                    .clickable { actions.onMode(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) Color.White else Palette.text.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SideButton(top: String, bottom: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(88.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.surface)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(top, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(bottom, color = Palette.muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun MicButton(state: KeyboardUiState, actions: KeyboardActions) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val wave by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wave",
    )
    val levelScale by animateFloatAsState(1f + state.level * 0.25f, label = "level")
    val bg = when {
        !state.micEnabled -> Palette.disabled
        state.listening -> Palette.listening
        else -> Palette.accent
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            if (state.listening) {
                Box(
                    Modifier
                        .size(76.dp)
                        .scale(maxOf(wave, levelScale))
                        .clip(CircleShape)
                        .background(Palette.listening.copy(alpha = 0.25f)),
                )
            }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(enabled = state.micEnabled, onClick = actions::onMic),
                contentAlignment = Alignment.Center,
            ) { MicGlyph(if (state.micEnabled) Color.White else Palette.muted) }
        }
        Text(
            text = when {
                !state.micEnabled -> "Mic off"
                state.listening -> if (state.language == Language.FA) "در حال شنیدن…" else "Listening…"
                else -> if (state.language == Language.FA) "ضربه برای صحبت" else "Tap to speak"
            },
            color = if (state.listening) Palette.text else Palette.muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MicGlyph(color: Color) {
    Canvas(Modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        // capsule
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.33f, 0f),
            size = Size(w * 0.34f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.17f),
        )
        // cradle
        drawArc(
            color = color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.18f, h * 0.18f), size = Size(w * 0.64f, h * 0.6f),
            style = Stroke(width = stroke),
        )
        // stem + base
        drawLine(color, Offset(w / 2, h * 0.78f), Offset(w / 2, h * 0.94f), strokeWidth = stroke)
        drawLine(color, Offset(w * 0.32f, h * 0.95f), Offset(w * 0.68f, h * 0.95f), strokeWidth = stroke)
    }
}

// ---------------------------------------------------------------- 4. quick edit bar

@Composable
private fun QuickEditBar(helpOpen: Boolean, actions: KeyboardActions) {
    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        QuickKey("↶", "Undo") { actions.onQuick(QuickAction.UNDO) }
        QuickKey("↷", "Redo") { actions.onQuick(QuickAction.REDO) }
        QuickKey("⌫", "Word") { actions.onQuick(QuickAction.DELETE_WORD) }
        QuickKey("⌫⌫", "Sentence") { actions.onQuick(QuickAction.DELETE_SENTENCE) }
        QuickKey("↵", "New line") { actions.onQuick(QuickAction.NEW_LINE) }
        // Commands cheat sheet; long-press opens WritingHelp settings.
        QuickKey(
            if (helpOpen) "✕" else "?", if (helpOpen) "Close" else "Commands",
            onLongClick = actions::onOpenSettings,
        ) { actions.onHelp() }
    }
}

@Composable
private fun RowScope.QuickKey(glyph: String, label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.surface)
            .then(
                if (onLongClick != null) Modifier.combinedClickableCompat(onClick, onLongClick)
                else Modifier.clickable(onClick = onClick)
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(glyph, color = Palette.text, fontSize = 17.sp, maxLines = 1)
        Text(label, color = Palette.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip)
    }
}
