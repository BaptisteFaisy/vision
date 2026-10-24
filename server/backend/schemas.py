"""Pydantic request models implementing the HTTP contract (docs/SPEC.md)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

MIN_IMAGE_CHARS = 8


class SegmentRequest(BaseModel):
    """POST /segment body: image plus optional concepts (null = server choice)."""

    image_b64: str = Field(min_length=MIN_IMAGE_CHARS)
    prompts: list[str] | None = None


class LiveRequest(BaseModel):
    """POST /live body (docs/LIVE-SPEC.md): one video frame plus tuning knobs.

    ``work_edge`` and ``max_objects`` are clamped server-side (never rejected) so a
    client sending an out-of-range knob still gets a frame back.
    """

    image_b64: str = Field(min_length=MIN_IMAGE_CHARS)
    prompts: list[str] | None = None
    max_objects: int | None = None
    work_edge: int | None = None


class AnalyzeRequest(BaseModel):
    """POST /analyze body: only the image is needed."""

    image_b64: str = Field(min_length=MIN_IMAGE_CHARS)


class HistoryMessage(BaseModel):
    """One turn of the /ask conversation history."""

    role: Literal["user", "assistant"]
    content: str


class AskRequest(BaseModel):
    """POST /ask body: image, question and an optional chat history."""

    image_b64: str = Field(min_length=MIN_IMAGE_CHARS)
    question: str = Field(min_length=1)
    history: list[HistoryMessage] | None = None