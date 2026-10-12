"""Minimal Claude API client over urllib: the Messages API for rewrites and the Models API for Settings."""

import json
import urllib.error
import urllib.request

from .prompts import ModelOption

API_URL = "https://api.anthropic.com/v1/messages"
MODELS_URL = "https://api.anthropic.com/v1/models?limit=100"
ANTHROPIC_VERSION = "2023-06-01"
MAX_OUTPUT_TOKENS = 4096
OPEN_TAG = "<output>"
CLOSE_TAG = "</output>"


class ClaudeError(Exception):
    def __init__(self, message: str, is_auth_error: bool = False):
        super().__init__(message)
        self.is_auth_error = is_auth_error


def _headers(api_key: str) -> dict:
    return {
        "x-api-key": api_key,
        "anthropic-version": ANTHROPIC_VERSION,
        "content-type": "application/json",
    }


def _unwrap_output(text: str) -> str:
    """Keeps only the text inside <output>...</output> when the reply has the tags."""
    start = text.find(OPEN_TAG)
    if start < 0:
        return text
    start += len(OPEN_TAG)
    end = text.find(CLOSE_TAG, start)
    return text[start:] if end < 0 else text[start:end]


def send_message(api_key: str, model: str, system_prompt: str, user_text: str) -> str:
    body = {
        "model": model,
        "max_tokens": MAX_OUTPUT_TOKENS,
        "system": system_prompt,
        "messages": [{"role": "user", "content": user_text}],
        # Low effort keeps latency and cost down for short, single-shot text transformations.
        "output_config": {"effort": "low"},
    }
    request = urllib.request.Request(
        API_URL, data=json.dumps(body).encode("utf-8"), headers=_headers(api_key), method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise ClaudeError(_error_message(e), is_auth_error=e.code in (401, 403)) from None
    except urllib.error.URLError as e:
        raise ClaudeError(f"Couldn't reach Claude: {e.reason}") from None
    except Exception as e:  # noqa: BLE001 - surfaced to the user as-is
        raise ClaudeError(f"Unexpected error: {e}") from None

    text = "".join(
        block.get("text", "") for block in payload.get("content", []) if block.get("type") == "text"
    )
    text = _unwrap_output(text).strip()
    if not text:
        raise ClaudeError("Claude returned an empty response. Please try again.")
    return text


def _error_message(error: urllib.error.HTTPError) -> str:
    try:
        message = json.loads(error.read().decode("utf-8")).get("error", {}).get("message")
        if message:
            return message
    except Exception:  # noqa: BLE001
        pass
    return f"Claude API error ({error.code})"


def list_models(api_key: str):
    """The models api_key can use, newest first, or None on any failure."""
    request = urllib.request.Request(MODELS_URL, headers=_headers(api_key), method="GET")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            data = json.loads(response.read().decode("utf-8")).get("data", [])
    except Exception:  # noqa: BLE001
        return None
    models = [
        ModelOption(item["id"], item.get("display_name") or item["id"])
        for item in data
        if str(item.get("id", "")).startswith("claude-")
    ]
    return models or None
