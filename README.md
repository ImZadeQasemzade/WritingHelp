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
