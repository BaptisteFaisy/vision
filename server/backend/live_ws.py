"""Contrat du WebSocket ``/live/ws`` : un message texte JSON par frame, dans les deux sens.

Module pur (stdlib + pydantic, ni FastAPI ni GPU) : ``live_app_fast.py`` ne fait que
brancher ce protocole sur le pipeline existant (``Sam3Live._live_sync``), une frame à la
fois par connexion. La forme de la réponse est **celle de ``POST /live``**, plus l'``id``.

    entrée  {"id": 7, "image_b64": "...", "prompts": ["person", "door"],
             "max_objects": 4, "work_edge": 640, "mask_encoding": "bits"}
    sortie  {"id": 7, "width": 512, "height": 1138, "elapsed_ms": 118,
             "mask_encoding": "bits",
             "objects": [{"label": "person", "score": 0.91, "box": [x1, y1, x2, y2],
                          "mask_b64": "..."}]}
    erreur  {"id": 7, "error": {"status": 400, "detail": "..."}}

``mask_encoding`` (``png`` par défaut, ``bits`` ou ``none``) est hérité de
``schemas.LiveWsRequest`` : le message WS et le corps de ``POST /live`` décrivent donc
exactement la même requête, et la réponse porte la représentation **appliquée**.

Une erreur de frame ne ferme jamais la connexion : ``id`` vaut ``-1`` quand il est
illisible (JSON invalide, ``id`` absent ou non entier).
"""

from __future__ import annotations

import json

from pydantic import ValidationError

from http_errors import MAX_DETAIL_CHARS, validation_detail
from schemas import LiveWsRequest

UNKNOWN_ID = -1
__all__ = ["UNKNOWN_ID", "error_message", "error_payload", "frame_id", "parse_frame",
           "success_message"]


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
