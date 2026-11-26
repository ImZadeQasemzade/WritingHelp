"""Modes, languages, models and system prompts. Kept in step with the Android app's data package."""

import re
from dataclasses import dataclass
from enum import Enum


class Mode(Enum):
    FIX_GRAMMAR = ("Fix Grammar", "Fixing grammar…", False)
    REWRITE = ("AI Rewrite", "Rewriting…", True)
    TRANSLATE = ("Translate", "Translating…", False)
    # The input is a request ("an email to my friend…"), not text to transform.
    WRITE = ("Write for me", "Writing…", True)

    def __init__(self, title, loading_label, supports_refinement):
        self.title = title
        self.loading_label = loading_label
        self.supports_refinement = supports_refinement


class Feature(Enum):
    """Actions the user can switch on or off in Settings (same keys as Android's Feature)."""

    FIX_GRAMMAR = ("feature_fix_grammar", "Fix Grammar",
                   "Corrects grammar, spelling and punctuation.", Mode.FIX_GRAMMAR)
    REWRITE = ("feature_rewrite", "AI Rewrite",
               "Rewrites your text to read better, with tone chips.", Mode.REWRITE)
    TRANSLATE = ("feature_translate", "Translate",
                 "Between your main and second language.", Mode.TRANSLATE)
    WRITE = ("feature_write", "Write for me",
             'Describe what you need ("an email to my friend…") and it writes it at the cursor. '
             "Also opens when nothing is selected.", Mode.WRITE)
    EXPLAIN = ("feature_explain", "Explain changes",
               'A "Why?" button after Fix Grammar or AI Rewrite shows short bullet points on '
               "what changed and why.", None)

    def __init__(self, key, title, description, mode):
        self.key = key
        self.title = title
        self.description = description
        self.mode = mode

    @staticmethod
    def for_mode(mode: "Mode") -> "Feature":
        return next(f for f in Feature if f.mode is mode)


@dataclass(frozen=True)
class Chip:
    label: str
    instruction: str


REFINEMENT_CHIPS = [
    Chip("Professional", "make the tone more professional and polished"),
    Chip("Friendly", "make the tone warmer and friendlier"),
    Chip("Concise", "make it more concise"),
    Chip("Shorter", "make it noticeably shorter"),
    Chip("Casual", "make the tone more casual and relaxed"),
]

# The name is what goes into the prompt, so it's the English name Claude knows the language by.
LANGUAGES = [
    "English", "Persian", "Arabic", "Chinese", "Dutch", "French", "German", "Hindi",
    "Italian", "Japanese", "Korean", "Portuguese", "Russian", "Spanish", "Turkish", "Urdu",
]
DEFAULT_MAIN_LANGUAGE = "English"
DEFAULT_SECOND_LANGUAGE = "Persian"


@dataclass(frozen=True)
class ModelOption:
    api_id: str
    display_name: str


DEFAULT_MODEL_ID = "claude-sonnet-5-5"

# Used until the live list has been fetched from the Models API.
FALLBACK_MODELS = [
    ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5"),
    ModelOption("claude-opus-5-5", "Claude Opus 5.5"),
    ModelOption("claude-haiku-5-5", "Claude Haiku 5.5"),
]


def description_for(api_id: str) -> str:
    if "opus" in api_id:
        return "Most capable. Best quality rewrites, slower and pricier."
    if "sonnet" in api_id:
        return "Great quality, cheaper and faster than Opus. Default."
    if "haiku" in api_id:
        return "Fastest and cheapest - good for quick grammar fixes."
    return "Claude model."


def default_model_from(options) -> str:
    """The newest Sonnet in options (the API lists newest first), else the first, else the default."""
    for option in options:
        if "sonnet" in option.api_id:
            return option.api_id
    return options[0].api_id if options else DEFAULT_MODEL_ID


# The reply is pasted straight into the user's text, so the model wraps the result in <output>
# tags and the client keeps only what's inside them, dropping any stray commentary.
TAG_RULES = (
    "Put the final text between <output> and </output>, and write nothing outside those "
    "tags: no preamble, no explanations, no notes about what you did. Inside the tags, "
    "no quotation marks around the text and no markdown formatting. Preserve line "
    "breaks and emoji where present."
)


def _output_rules(main: str) -> str:
    """Fix Grammar and AI Rewrite always answer in the user's main language."""
    return (
        f"Write the result in {main}, in its native script. If the input is in another "
        f"language, translate it into {main} as part of this. Do this silently and never "
        f"mention the languages. {TAG_RULES}"
    )


def system_prompt_for(mode: Mode, main: str, second: str, refinement: str | None = None) -> str:
    if mode is Mode.FIX_GRAMMAR:
        return (
            "You are a precise grammar and spelling correction assistant for short "
            "personal messages (texts, chats, emails). Correct grammar, spelling, and "
            "punctuation mistakes while preserving the author's exact meaning, tone, and "
            "style. Do not rephrase sentences that are already correct, and do not make "
            f"the message more formal than the original. {_output_rules(main)}"
        )
    if mode is Mode.WRITE:
        return (
            "You are a writing assistant. The user tells you what they want written, for "
            'example "an email to my friend inviting her to dinner on Saturday", and '
            "you write it for them, ready to send. Match the tone the request implies "
            "(casual for friends, polished for work), keep it natural and appropriately "
            "short, and don't invent specific facts such as names, dates or places beyond "
            "what the request gives; where something is missing, keep the wording general "
            f"instead of adding placeholders in brackets. Write in {main}, in its "
            f"native script, unless the request asks for another language. {TAG_RULES}"
        )
    if mode is Mode.REWRITE:
        prompt = (
            "You are a writing assistant that rewrites short personal messages to be "
            "clearer and better written, while preserving the original meaning and intent. "
        )
        if refinement:
            prompt += f"Specifically, {refinement}. "
        return prompt + _output_rules(main)
    return (
        "You are a translator for short personal messages (texts, chats, emails). "
        f"If the input is in {main}, translate it into {second}. If the input is in any "
        f"other language, translate it into {main}. Write the translation in the target "
        "language's native script. Keep the meaning, tone, and level of formality of the "
        "original, and use natural, everyday phrasing rather than a word-for-word "
        "translation. Decide the direction silently and never mention the languages. "
        f"{TAG_RULES}"
    )


def explain_prompt(main: str) -> str:
    """ "Why?": short, teachable bullet points on what changed between the text and the result."""
    return (
        "You are a friendly writing tutor helping someone learn to write better. You get their "
        "ORIGINAL text and a REVISED version. Explain the most useful changes in 2 to 6 "
        "bullet points. Keep every bullet short (under 20 words): quote the change briefly "
        "and name the reason or rule, for example: 'has went' → 'has gone': use the past "
        "participle after 'has'. Focus on what helps them write better next time (grammar "
        "rules, word choice, clarity, tone) and skip trivial or repeated changes. If nothing "
        "meaningful changed, give one bullet saying the text was already fine. Write the "
        f"explanations in {main}, keeping the quoted words as they appear. Put "
        f'each bullet on its own line, starting with "• ". {TAG_RULES}'
    )


def explain_input(original: str, revised: str) -> str:
    return f"ORIGINAL:\n{original}\n\nREVISED:\n{revised}"


_BULLET_PREFIX = re.compile(r"^\s*(?:[•·▪●◦*\-–—]+|\d+[.)])\s*")


def parse_bullets(reply: str) -> list[str]:
    """One bullet per non-blank line, with any leading marker or numbering ("•", "-", "1.") removed."""
    bullets = (_BULLET_PREFIX.sub("", line, count=1).strip() for line in reply.splitlines())
    return [b for b in bullets if b]
