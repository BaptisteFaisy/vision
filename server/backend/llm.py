"""OpenAI-compatible vision LLM helpers.

One client, one chat call path, shared prompt plumbing: /analyze and /ask only
differ by their prompt, so everything else is reused from here.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from functools import lru_cache

from openai import OpenAI

DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
DEFAULT_MODEL = "google/gemini-2.5-flash"
REQUEST_TIMEOUT = 90.0
TEMPERATURE = 0.2
# muse-spark raisonne avant de répondre (tokens comptés dans le budget) : à 800,
# /analyze (JSON contraint) rendait systématiquement un contenu vide et /ask avec
# historique de façon intermittente (finish `length` — 29/10). Budget unique large :
# le coût suit les tokens utilisés, pas le plafond.
MAX_TOKENS = 3000
MAX_HISTORY_MESSAGES = 20
MAX_TAGS = 8

SYSTEM_PROMPT = (
    "Tu es un assistant visuel francophone. Tu décris des photos de façon factuelle, "
    "sans inventer ce que tu ne vois pas, et tu réponds toujours en français."
)
ASK_SYSTEM_PROMPT = (
    "Tu réponds en français, en une à trois phrases, uniquement à partir de la photo et "
    "de l'historique fournis. Si l'information n'est pas visible, dis-le simplement."
)
DESCRIBE_TEMPLATE = (
    "Décris cette photo en français, en 2 à 4 phrases. "
    "Tu dois citer explicitement les objets détectés par la segmentation : {labels}. "
    "N'invente aucun objet absent de cette liste. "
    'Réponds UNIQUEMENT avec un JSON de la forme '
    '{{"description": "<texte>", "tags": ["<tag court>", "..."]}} avec 3 à 8 tags.'
)


class LLMError(RuntimeError):
    """Raised when the vision LLM is misconfigured or its call fails (HTTP 500)."""


@dataclass(frozen=True)
class LLMSettings:
    """Resolved LLM endpoint configuration."""

    base_url: str
    api_key: str
    model: str


def settings_from_env() -> LLMSettings:
    """Read LLM_BASE_URL / LLM_API_KEY / LLM_MODEL (Modal secret ``sam3-llm``)."""
    api_key = os.environ.get("LLM_API_KEY", "").strip()
    if not api_key:
        raise LLMError("LLM_API_KEY is missing (expected in Modal secret 'sam3-llm')")
    return LLMSettings(
        base_url=os.environ.get("LLM_BASE_URL", "").strip() or DEFAULT_BASE_URL,
        api_key=api_key,
        model=os.environ.get("LLM_MODEL", "").strip() or DEFAULT_MODEL,
    )


@lru_cache(maxsize=4)
def _client(base_url: str, api_key: str) -> OpenAI:
    return OpenAI(base_url=base_url, api_key=api_key, timeout=REQUEST_TIMEOUT)


def _chat(settings: LLMSettings, messages: list[dict]) -> str:
    """Send one vision chat completion and return its text content."""
    try:
        completion = _client(settings.base_url, settings.api_key).chat.completions.create(
            model=settings.model,
            messages=messages,
            temperature=TEMPERATURE,
            max_tokens=MAX_TOKENS,
        )
    except Exception as exc:  # SDK, transport and provider errors all surface as 500
        raise LLMError(f"LLM call failed: {exc}") from exc
    content = (completion.choices[0].message.content or "").strip()
    if not content:
        raise LLMError("LLM returned an empty answer")
    return content


def user_message(data_url: str, text: str) -> dict:
    """Build a multimodal user turn (text + image)."""
    return {
        "role": "user",
        "content": [
            {"type": "text", "text": text},
            {"type": "image_url", "image_url": {"url": data_url}},
        ],
    }


def label_hint(labels: list[str]) -> str:
    """Human-readable list of segmented labels for the prompt."""
    return ", ".join(labels) if labels else "aucun objet segmenté"


def _first_json_object(text: str) -> str:
    match = re.search(r"\{.*\}", text, re.DOTALL)
    return match.group(0) if match else ""


def extract_json(text: str) -> dict | None:
    """Best-effort JSON extraction from an LLM answer (markdown fences tolerated)."""
    candidate = re.sub(r"^\s*```[a-zA-Z]*|```\s*$", "", text.strip()).strip()
    for chunk in (candidate, _first_json_object(candidate)):
        try:
            parsed = json.loads(chunk)
        except (json.JSONDecodeError, TypeError):
            continue
        if isinstance(parsed, dict):
            return parsed
    return None


def describe_image(data_url: str, labels: list[str], settings: LLMSettings) -> tuple[str, list[str]]:
    """Ask for a description citing the segmented labels, plus short tags."""
    prompt = DESCRIBE_TEMPLATE.format(labels=label_hint(labels))
    raw = _chat(settings, [{"role": "system", "content": SYSTEM_PROMPT}, user_message(data_url, prompt)])
    parsed = extract_json(raw)
    if not parsed:
        return raw, list(labels)
    description = str(parsed.get("description") or "").strip() or raw
    tags = [str(tag).strip() for tag in parsed.get("tags") or [] if str(tag).strip()]
    return description, tags[:MAX_TAGS]


def _history_messages(history: list[dict] | None) -> list[dict]:
    """Keep the last valid turns of the client-provided history."""
    messages: list[dict] = []
    for item in (history or [])[-MAX_HISTORY_MESSAGES:]:
        if not isinstance(item, dict):
            continue
        role = item.get("role")
        content = str(item.get("content") or "").strip()
        if role in {"user", "assistant"} and content:
            messages.append({"role": role, "content": content})
    return messages


def answer_question(data_url: str, question: str, history: list[dict] | None, settings: LLMSettings) -> str:
    """Answer a question about the photo, replaying the optional chat history."""
    messages = [{"role": "system", "content": ASK_SYSTEM_PROMPT}]
    messages.extend(_history_messages(history))
    messages.append(user_message(data_url, question.strip()))
    return _chat(settings, messages)