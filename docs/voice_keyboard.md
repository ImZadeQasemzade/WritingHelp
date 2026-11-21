# Voice Writing Keyboard — developer notes

The voice keyboard is an Android input method inside WritingHelp. Users dictate in English
(en-US) or Persian (fa-IR) and edit the text by voice. User-facing setup is in the README; this
file is for working on the code.

All paths below are under `app/src/main/java/com/writinghelp/rewrite/voice/`.

## Layout

```
core/    pure Kotlin, unit tested on the JVM
  Types.kt                Language, InputMode, CommandType, KeyboardIntent, IntentClassifier
  CommandPhrases.kt       THE phrase table (regexes, EN + FA)
  CommandGuide.kt         human-readable examples shown in the ? panel and Settings
  RuleBasedClassifier.kt  regex matcher; mode names said alone switch mode from any mode
  EditorEngine.kt         dictation, commands, typed keys, range (smart) edits over a TextField
  EditHistory.kt          the keyboard's own undo/redo stack
  DictationFormatter.kt   spacing, capitalisation, Arabic → Persian letters
  WordDiff.kt             word diff for the smart-edit preview
  TextField.kt            what the editor needs from a field
speech/SpeechController.kt  SpeechRecognizer wrapper: continuous listening, restarts, errors
ime/     VoiceKeyboardService (lifecycle, wiring), KeyboardController (logic + UI state),
         InputConnectionField (TextField over InputConnection)
ui/      KeyboardScreen (Compose)
llm/     LlmClient, ClaudeLlmClient (over network/ClaudeApiClient), LlmCommandParser, SmartPipeline
```

Settings live in WritingHelp's `SettingsRepository` (`voice_*` keys plus the shared API key and
model). The settings UI is `ui/VoiceKeyboardSection.kt`, shown in `MainActivity`.

## Adding a command phrase

1. Add a regex string to the right entry in `core/CommandPhrases.kt`, e.g.
   `r(CommandType.UNDO, "undo", "undo that", "go back")`.
   Patterns match the *whole* phrase after normalisation: lower case, trailing punctuation
   removed, Arabic ي/ك → Persian ی/ک, half-space (ZWNJ) → space, spaces collapsed. Capture groups
   become arguments (REPLACE takes two).
2. If it's a good example for users, add it to `core/CommandGuide.kt`. `CommandGuideTest` fails
   if an example there isn't understood.
3. Add a case to `RuleBasedClassifierTest` and run `./gradlew testDebugUnitTest`.

A new *kind* of command needs a `CommandType`, a method in `EditorEngine`, a branch in
`KeyboardController.runCommand`, an entry in `CommandGuide`, and a Persian title in
`KeyboardScreen.faTitle`.

## How undo works

Every change is stored relative to the cursor — the text removed and inserted on each side of
it — not as absolute offsets, so it works in any app, including apps with no undo of their own.
Undo first checks that the text around the cursor is still what the keyboard left there. If the
user typed or moved the cursor in between, it says "Text changed — can't undo" instead of
damaging the text. If the app won't let the keyboard read the field, it trusts its own history.
Each dictated phrase, command, tapped key and smart edit is one undo step.

## Smart edits

`SmartPipeline` runs in AUTO and COMMAND mode: rules first; if nothing matched and the phrase
looks like an instruction (or the mode is COMMAND), `LlmCommandParser` asks Claude; otherwise the
phrase is dictated. It is skipped when smart edits are off, there's no API key, the device is
offline, or the field is a password / incognito field (each said once per session).

The model must reply with JSON only:

```json
{"action": "replace_range|insert|delete_range|dictation|none",
 "start": 0, "end": 0, "original": "text being replaced", "text": "new text", "summary": "Made formal"}
```

Validation is strict (unknown action, non-integer or out-of-range offsets → "Couldn't apply",
nothing changed). `original` lets the parser detect miscounted offsets and re-locate the span;
if `original` isn't in the text at all, the edit is rejected. `EditorEngine.applyRangeEdit`
re-checks that the field still holds the same text before applying. 8 s timeout via a
cancellable OkHttp call in `ClaudeApiClient`.

## Bottom spacing

With gesture navigation, Android draws its ⌄ / 🌐 buttons over the bottom of the keyboard
window. `VoiceKeyboardService.updateBottomInset` pads the keyboard by the navigation-bar inset,
falling back to the system's `navigation_bar_frame_height` in gesture mode when the window
reports no inset.

## Known limitations

- The system speech engine plays its start sound on each auto-restart on some phones and pauses
  briefly between phrases. Apps can't change that.
- Some apps (e.g. certain chat apps) declare no editor action, so *send* can't press their button.
- Undo covers the keyboard's own changes, not text typed with another keyboard.
- Saying only a mode name ("command", «دستور» …) always switches mode, so those single words
  can't be dictated on their own.
