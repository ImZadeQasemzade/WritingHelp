# WritingHelp

An Android app that adds **"Fix Grammar"** and **"Rewrite with AI"** to the text-selection
popup (next to Copy/Cut/Paste) in *any* app on your phone — Messages, WhatsApp, Gmail, etc. —
powered by the Claude API.

## How it works

Android has a public mechanism for this: [`ACTION_PROCESS_TEXT`](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT).
Any app that declares an activity with that intent filter gets added to the system
text-selection menu. This app declares two such activities — `FixGrammarActivity` and
`RewriteActivity` — each showing up as its own entry.

1. You select text anywhere on your phone.
2. You tap "Fix Grammar" or "Rewrite with AI" in the popup that appears next to Copy/Paste.
3. A small floating card opens, sends the selected text to the Claude API, and shows the result.
4. If the text came from an editable field (a message you're composing), you get a **Replace**
   button that swaps your selection for the AI's version in place. If it came from read-only
   text (e.g. a webpage), you get **Copy** instead, since there's nothing to replace it in.
5. For "Rewrite with AI" specifically, you can tap a chip (Professional / Friendly / Concise /
   Shorter / Casual) to redo the rewrite with that instruction, without leaving the popup.

### Floating bubble

For apps whose selection menu doesn't show these entries (Telegram, for example), turn on the
floating bubble in Settings. Tap it and pick an action. It works on your selection, or on the
whole field when nothing is selected, and replaces the text in place:

- **Fix Grammar**, **AI Rewrite**, **Translate**: same as the selection-menu entries.
- **Write for me**: type what you need (e.g. "an email to my friend about Saturday") and it
  writes it at your cursor, or in place of your selection. With no text field focused, the
  result goes to the clipboard.
- After a result: **Redo**, **Undo**, tone chips (AI Rewrite / Write for me), and **Why?**
  (Fix Grammar / AI Rewrite), which opens a card above or below the bubble with short bullet
  points on what changed and why, so you can learn from the edits.

Every action can be switched on or off under **Settings ▸ Actions**. Switching off Fix Grammar,
AI Rewrite or Translate also removes it from the text-selection menu.

### Voice Writing Keyboard

WritingHelp also includes a voice-first keyboard (an Android input method). Switch to it in
any app to dictate in English or Persian, and fix the text by voice without the regular keyboard.
Set it up in **Settings ▸ Voice Writing Keyboard**: allow the microphone, turn the keyboard on,
then switch to it. Tap ? on the keyboard for the list of voice commands (also under **Voice commands** in Settings); long-press ? to open Settings.

- **Layout:** live transcript strip (with a green chip after a command), alternate-phrase
  suggestions, EN/FA toggle, big mic (continuous listening), AUTO / DICTATE / COMMAND mode, and
  quick keys: Undo, Redo, Delete word, Delete sentence, New line, ? (commands).
- **Commands** (EN / FA): undo / برگرد · redo / دوباره · delete last word / آخرین کلمه رو پاک کن ·
  delete last sentence / آخرین جمله رو پاک کن · replace X with Y / X رو به Y تغییر بده ·
  new line / خط جدید · send / بفرست. All phrases are in one file,
  [`voice/core/CommandPhrases.kt`](app/src/main/java/com/writinghelp/rewrite/voice/core/CommandPhrases.kt).
- **Undo** uses the keyboard's own history, so it works in apps that have no undo. It refuses
  rather than damaging text if you've changed the field by hand since.
- **Smart voice edits** (off by default): spoken edits the commands can't do, such as "make the
  last sentence more formal", "fix the grammar" or "translate that to Persian", go to Claude with
  up to ~2,000 characters around the cursor. They use the same API key and model as the rest of
  WritingHelp. The reply must be strict JSON (`replace_range` / `insert` / `delete_range` /
  `dictation` / `none` with offsets plus the `original` text, so wrong offsets can be detected);
  anything invalid shows "Couldn't apply" and changes nothing. Each edit is one undo step, with an
  8 s timeout and an optional ✓ / ✗ preview. Never used in password or incognito fields.

Code: `voice/core` (classifier, editor, history: pure Kotlin, unit tested), `voice/speech`
(SpeechRecognizer wrapper), `voice/ime` (the service), `voice/ui` (Compose keyboard),
`voice/llm` (smart-edit parser and pipeline over `ClaudeApiClient`).

There's no iOS equivalent of this feature: unlike Android, iOS doesn't let third-party apps add
entries to the system-wide text-selection menu in other apps.

## Setup

1. Get a Claude API key from the [Anthropic Console](https://console.anthropic.com/).
2. Open this project in Android Studio (Koala or newer). It will offer to sync/regenerate the
   Gradle wrapper if needed — accept that, then let Gradle sync run.
3. Build & run on your device (`Run ▸ app`, or `./gradlew installDebug`).
4. Open the WritingHelp app once, paste your API key into Settings, pick a model, and tap
   **Save**. Use **Test connection** to confirm the key works.
5. Go to any app, select some text, and look for "Fix Grammar" / "Rewrite with AI" in the
   popup. On some keyboards/launchers only 1-2 actions show inline and the rest are under a
   "..." overflow entry — that placement is controlled by the OS/keyboard, not this app.

## Design notes

- **API key storage**: the key is stored in `EncryptedSharedPreferences`
  (`androidx.security.crypto`), backed by the Android Keystore, and is only ever sent directly
  from your device to `api.anthropic.com` — never to any server of ours (there isn't one). This
  is the simplest approach for a personal-use app; the tradeoff is that the key lives on the
  device, so if you lose the phone or the app is compromised, so is the key. A backend-proxy
  architecture (the app talks to your own server, which holds the key) avoids that, at the cost
  of standing up and maintaining a server — not implemented here, but straightforward to add
  later behind the same `ClaudeApiClient` interface if you want it.
- **Direct HTTP, not the Anthropic Java SDK**: `network/ClaudeApiClient.kt` calls
  `POST /v1/messages` directly with OkHttp instead of pulling in Anthropic's official Java SDK.
  That SDK is built for backend JVM services; bundling it into a phone app for a single endpoint
  would add a lot of weight (its own HTTP stack, JSON binding, etc.) for no benefit over a plain
  REST call — OkHttp is already the standard, lightweight choice for Android networking.
- **Model / effort**: defaults to Claude Opus 5, selectable in Settings (Sonnet 5 and Haiku 4.5
  are also offered — cheaper/faster, since this is a small, repeated, low-latency task). Requests
  use `output_config.effort = "low"` to keep responses quick and cheap for this kind of short,
  single-shot text rewrite.
- **Two activities, one shared implementation**: `FixGrammarActivity` and `RewriteActivity` both
  extend `ProcessTextBaseActivity`, differing only in which `RewriteMode` (and therefore which
  system prompt) they use. Android needs them as separate manifest entries because the label
  shown in the selection popup is per-activity.

## Project structure

```
app/src/main/java/com/writinghelp/rewrite/
├── MainActivity.kt                 Settings screen (API key, model)
├── ProcessTextBaseActivity.kt      Shared ACTION_PROCESS_TEXT handling
├── FixGrammarActivity.kt           "Fix Grammar" entry point
├── RewriteActivity.kt              "Rewrite with AI" entry point
├── ProcessTextViewModel.kt         Popup screen state + Claude calls
├── data/
│   ├── RewriteMode.kt              FIX_GRAMMAR / REWRITE
│   ├── Prompts.kt                  System prompts + refinement chips
│   ├── ClaudeModelOption.kt        Selectable models
│   └── SettingsRepository.kt       Encrypted API key / model storage
├── network/
│   └── ClaudeApiClient.kt          Claude Messages API client (OkHttp)
└── ui/
    ├── ProcessTextScreen.kt        Popup UI (loading / result / error)
    ├── SettingsScreen.kt           Settings UI
    └── theme/Theme.kt              Compose Material 3 theme
```

## Windows app

`windows/` holds a Windows tray app with the same actions as the Android bubble (Fix Grammar,
AI Rewrite with the tone chips, Translate, Write for me, and Why? explanations), the same
prompts, the same main/second language settings, the same live model list (Sonnet by default)
and the same blue pencil icon. The voice keyboard is Android only.

Windows doesn't let one app add entries to other apps' right-click menus, so you select text
anywhere and either press **Ctrl+Shift+Space** or **Ctrl + right-click**. A small menu opens at the
pointer; pick an action, then **Replace** pastes the result over your selection (or **Copy** it).
After Fix Grammar or AI Rewrite, **Why?** shows short bullet points on what changed and why.

**Write for me**: press the shortcut with nothing selected (or pick it from the menu), describe
what you need, e.g. "an email to my friend about Saturday", and **Insert** puts the result at the
cursor (or **Replace** puts it over the selection). Tone chips work on the result.

Settings (tray icon → Settings…) switch each action on or off, and the two triggers and
"Start with Windows". Settings live in `%APPDATA%\WritingHelp`, with the API key encrypted by
Windows DPAPI.

How it reads the selection: it sends Ctrl+C to the focused app, reads the clipboard, and puts
your previous clipboard text straight back (pasting works the same way with Ctrl+V). Text only:
if the clipboard held an image, it's replaced by the selection.

Build (Python 3.11+):

```
cd windows
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python build.py      # -> exe\WritingHelp.exe
```

Run from source with `.venv\Scripts\python run.py`, and the tests with
`.venv\Scripts\python -m unittest discover tests`.
