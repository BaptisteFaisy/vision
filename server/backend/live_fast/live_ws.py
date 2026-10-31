"""Contrat du WebSocket ``/live/ws`` : un message texte JSON par frame, dans les deux sens.

Module pur (stdlib + pydantic, ni FastAPI ni GPU) : ``live_app_fast.py`` ne fait que
brancher ce protocole sur le pipeline existant (``Sam3Live._live_sync``), une frame à la
fois par connexion. La forme de la réponse est **celle de ``POST /live``**, plus l'``id``.

    entrée  {"id": 7, "image_b64": "...", "prompts": ["person", "door"],
             "max_objects": 4, "work_edge": 640, "mask_encoding": "bits",
             "with_depth": true}
    sortie  {"id": 7, "width": 512, "height": 1138, "elapsed_ms": 118,
             "mask_encoding": "bits",
             "objects": [{"label": "person", "score": 0.91, "box": [x1, y1, x2, y2],
                          "mask_b64": "...", "depth": 0.83}]}
    erreur  {"id": 7, "error": {"status": 400, "detail": "..."}}

``mask_encoding`` (``png`` par défaut, ``bits`` ou ``none``) est hérité de
``schemas.LiveWsRequest`` : le message WS et le corps de ``POST /live`` décrivent donc
exactement la même requête, et la réponse porte la représentation **appliquée**.
``with_depth`` (``false`` par défaut) ajoute à chaque objet sa médiane de proximité
(``depth``, 0..1, grand = proche) ; absent, la clé n'existe pas.

Une erreur de frame ne ferme jamais la connexion : ``id`` vaut ``-1`` quand il est
illisible (JSON invalide, ``id`` absent ou non entier).

Trame binaire M1 (même réponse texte) : uint32 big-endian N + en-tête JSON UTF-8
(les champs de ``LiveWsRequest`` **sans** ``image_b64``) + JPEG brut. Zéro base64
à la montée ; la réponse et les erreurs sont inchangées (``id`` de l'en-tête).
"""

from __future__ import annotations

import json
import struct

from pydantic import ValidationError

from http_errors import MAX_DETAIL_CHARS, validation_detail
from schemas import LiveWsRequest

UNKNOWN_ID = -1
# En-tête binaire > 4 Ko = 400 (des knobs tiennent en ~200 o ; borne anti-abus).
MAX_WS_HEADER_BYTES = 4096
HEADER_LEN_BYTES = 4
# ``LiveWsRequest`` exige ``image_b64`` : l'en-tête binaire n'en a pas (le JPEG
# suit en brut), on bouche avec ce factice — jamais décodé, jamais servi.
_BINARY_IMAGE_PLACEHOLDER = "M1-binary-frame"
__all__ = ["HEADER_LEN_BYTES", "MAX_WS_HEADER_BYTES", "UNKNOWN_ID", "error_message",
           "error_payload", "frame_id", "parse_frame", "parse_frame_bytes",
           "stuck_id", "success_message"]


def frame_id(value) -> int:
    """``id`` de corrélation tel qu'il est exploitable : ``-1`` si absent ou pas un entier."""
    return value if isinstance(value, int) and not isinstance(value, bool) else UNKNOWN_ID


def error_payload(status: int, detail: str) -> dict:
    """Corps ``error`` du contrat : statut (400|500) et détail borné."""
    return {"status": int(status), "detail": str(detail)[:MAX_DETAIL_CHARS]}


def error_message(frame_id_: int, status: int, detail: str) -> dict:
    """Message d'erreur complet : l'``id`` connu (ou ``-1``) et le corps d'erreur."""
    return {"id": frame_id(frame_id_), "error": error_payload(status, detail)}


def success_message(result: dict, frame_id_: int) -> dict:
    """Réponse WS : exactement la réponse de ``POST /live``, préfixée de l'``id``."""
    return {"id": frame_id(frame_id_), **result}


def parse_frame(raw: str) -> dict:
    """Message texte → ``{"id", "request", "error"}`` (``request``/``error`` exclusifs).

    L'``id`` est celui du message dès qu'il est lisible, même quand le reste est
    invalide : le client peut ainsi corréler l'erreur à la frame qu'il a envoyée.
    """
    try:
        payload = json.loads(raw)
    except (TypeError, ValueError) as exc:
        return {"id": UNKNOWN_ID, "request": None,
                "error": error_payload(400, f"invalid JSON: {exc}")}
    if not isinstance(payload, dict):
        return {"id": UNKNOWN_ID, "request": None,
                "error": error_payload(400, "invalid request body: expected a JSON object")}
    known_id = frame_id(payload.get("id"))
    try:
        request = LiveWsRequest(**payload)
    except ValidationError as exc:
        return {"id": known_id, "request": None,
                "error": error_payload(400, validation_detail(exc.errors()))}
    return {"id": request.id, "request": request, "error": None}


def _binary_error(known_id: int, detail: str) -> dict:
    """Erreur 400 d'une trame binaire : ``id`` connu (ou ``-1``), connexion gardée."""
    return {"id": known_id, "request": None, "jpeg": b"",
            "error": error_payload(400, detail)}


def parse_frame_bytes(raw: bytes) -> dict:
    """Trame binaire → ``{"id", "request", "jpeg", "error"}`` (``error`` exclusif).

    Même contrat que :func:`parse_frame`, le JPEG voyageant en brut après
    l'en-tête : ``request`` porte les knobs validés (``image_b64`` bouché, à
    ignorer), ``jpeg`` les octets à décoder. Longueur manquante, en-tête > 4 Ko,
    tronqué ou non-JSON, ``id`` absent, JPEG vide : 400, connexion maintenue.
    """
    if not isinstance(raw, (bytes, bytearray)) or len(raw) < HEADER_LEN_BYTES:
        return _binary_error(UNKNOWN_ID, "truncated binary frame: missing header length")
    (size,) = struct.unpack(">I", bytes(raw[:HEADER_LEN_BYTES]))
    if size > MAX_WS_HEADER_BYTES:
        return _binary_error(UNKNOWN_ID,
                             f"binary header too large: {size}B > {MAX_WS_HEADER_BYTES}B")
    if len(raw) < HEADER_LEN_BYTES + size:
        return _binary_error(UNKNOWN_ID, "truncated binary frame: header exceeds message")
    try:
        payload = json.loads(bytes(raw[HEADER_LEN_BYTES:HEADER_LEN_BYTES + size]).decode("utf-8"))
    except (TypeError, ValueError) as exc:
        return _binary_error(UNKNOWN_ID, f"invalid binary header: {exc}")
    if not isinstance(payload, dict):
        return _binary_error(UNKNOWN_ID, "invalid binary header: expected a JSON object")
    known_id = frame_id(payload.get("id"))
    payload["image_b64"] = _BINARY_IMAGE_PLACEHOLDER
    try:
        request = LiveWsRequest(**payload)
    except ValidationError as exc:
        return _binary_error(known_id, validation_detail(exc.errors()))
    jpeg = bytes(raw[HEADER_LEN_BYTES + size:])
    if not jpeg:
        return _binary_error(request.id, "empty JPEG payload in binary frame")
    return {"id": request.id, "request": request, "jpeg": jpeg, "error": None}


def stuck_id(raw) -> int:
    """``id`` d'une frame lente (texte ou binaire) : ``-1`` si illisible.

    Voie unique de l'erreur 503 : l'en-tête binaire est relu comme à la
    réception, un texte non-JSON rend ``-1`` — jamais d'exception.
    """
    if isinstance(raw, (bytes, bytearray)):
        return parse_frame_bytes(bytes(raw))["id"]
    if not isinstance(raw, str):
        return UNKNOWN_ID
    try:
        payload = json.loads(raw)
    except (TypeError, ValueError):
        return UNKNOWN_ID
    return frame_id(payload.get("id")) if isinstance(payload, dict) else UNKNOWN_ID
