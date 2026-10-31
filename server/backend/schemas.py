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

    ``mask_encoding`` choisit la représentation de ``mask_b64`` (``png`` par défaut —
    contrat historique des APK installés —, ``bits`` pour le codec 1 bit/pixel + zlib,
    ``none`` pour ne renvoyer que les boîtes). Absent = ``png`` : un client muet est servi
    exactement comme avant. Déclaré ici, il est hérité par ``LiveFastRequest`` et
    ``LiveWsRequest`` : ``POST /live`` et ``WS /live/ws`` négocient donc d'un seul coup.
    """

    image_b64: str = Field(min_length=MIN_IMAGE_CHARS)
    prompts: list[str] | None = None
    max_objects: int | None = None
    work_edge: int | None = None
    # Profondeur fusionnée : chaque objet porte sa médiane de proximité
    # (``depth``, 0..1, grand = proche). Absent = False : un client muet est
    # servi exactement comme avant, sans clé et sans coût GPU.
    with_depth: bool = False
    mask_encoding: Literal["bits", "png", "none"] | None = None
    # Codec compact : "rle" (+ mask_edge, défaut 256) ; toute autre valeur est
    # ignorée (chemin historique), jamais rejetée — un knob ne vaut pas un 400.
    mask_format: str | None = None
    mask_edge: int | None = None


class LiveFastRequest(LiveRequest):
    """POST /live body pour ``live_app_fast.py`` : deux leviers par requête en plus.

    ``batch_prompts`` (0/1) et ``edge`` surchargent les défauts du conteneur
    (``FAST_BATCH_PROMPTS`` / ``FAST_EDGE``) pour mesurer un levier à la fois sur un
    seul conteneur. Absent = défaut du conteneur : les clients existants ne changent pas.
    """

    batch_prompts: Literal[0, 1] | None = None
    edge: int | None = Field(default=None, ge=112, le=1512)


class LiveWsRequest(LiveFastRequest):
    """Message du WebSocket ``/live/ws`` : le corps de ``POST /live`` plus un id.

    ``id`` est **obligatoire** (absent → erreur de validation) : c'est lui qui apparie la
    réponse à la frame envoyée, plusieurs frames s'enchaînant sur la même connexion.
    ``image_b64`` reste obligatoire ; ``batch_prompts``/``edge`` restent optionnels, donc
    une frame WS et un corps ``POST /live`` décrivent exactement la même requête.
    """

    id: int


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

class TrackSessionRequest(BaseModel):
    """POST /session body (docs/TRACK-SPEC.md): frame 0 plus the concepts to track.

    ``capacity`` = nombre de frames que le client s'engage à envoyer (le tampon GPU est
    pré-alloué à cette taille : c'est lui qui dimensionne la VRAM de la session, d'où le
    réglage par requête et pas par variable d'environnement). ``debug`` ajoute le relevé
    mémoire par frame — coûteux, réservé au diagnostic.
    """

    image_b64: str = Field(min_length=MIN_IMAGE_CHARS)
    prompts: list[str] | None = None
    capacity: int | None = None
    debug: bool = False


class TrackFrameRequest(BaseModel):
    """POST /frame body: one stream frame for an existing session."""

    session_id: str = Field(min_length=1, max_length=64)
    image_b64: str = Field(min_length=MIN_IMAGE_CHARS)


class TrackCloseRequest(BaseModel):
    """POST /session/close body: session to release."""

    session_id: str = Field(min_length=1, max_length=64)
