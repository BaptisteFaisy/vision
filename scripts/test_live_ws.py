#!/usr/bin/env python3
"""Contrat du WebSocket ``/live/ws``, sans GPU ni réseau : l'app ASGI est pilotée à la main.

Le pipeline (``Sam3Live._live_sync``) est remplacé par un stub : on vérifie la plomberie
(``accept``, un message texte par frame, ``id`` renvoyé), la forme de la réponse —
identique à celle de ``POST /live`` — et le fait qu'une frame invalide (JSON cassé, ``id``
absent, base64 illisible, pipeline en erreur, message binaire) renvoie une erreur **sans
fermer la connexion**. Les stubs ``modal``/``torch`` et l'image factice viennent de
``test_units.py``.

    python3 scripts/test_live_ws.py
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

from pydantic import ValidationError

sys.path.insert(0, str(Path(__file__).resolve().parent))

from test_units import (BACKEND_DIR, FAILED, PASSED, _live_service, asgi_call,  # noqa: E402
                        check, install_stubs, raises, sample_png)

LIVE_KEYS = {"width", "height", "elapsed_ms", "objects"}
WS_KEYS = LIVE_KEYS | {"id"}


async def ws_session(api, messages) -> list[dict]:
    """Session WebSocket jouée sur l'app ASGI : renvoie les messages émis vers le client."""
    pending = [{"type": "websocket.connect"}]
    for item in messages:
        event = "bytes" if isinstance(item, bytes) else "text"
        pending.append({"type": "websocket.receive", event: item})
    pending.append({"type": "websocket.disconnect", "code": 1000})
    sent: list[dict] = []

    async def receive():
        return pending.pop(0) if pending else {"type": "websocket.disconnect", "code": 1000}

    async def send(message):
        sent.append(message)

    scope = {"type": "websocket", "asgi": {"version": "3.0"}, "http_version": "1.1",
             "scheme": "ws", "path": "/live/ws", "raw_path": b"/live/ws", "query_string": b"",
             "root_path": "", "headers": [], "subprotocols": [], "client": ("test", 1),
             "server": ("test", 80), "app": api}
    await api(scope, receive, send)
    return sent


def replies(sent: list[dict]) -> list[dict]:
    """Les messages texte du serveur, désérialisés dans l'ordre d'envoi."""
    return [json.loads(item["text"]) for item in sent if item["type"] == "websocket.send"]


def frame(ident: int, png: str, **extra) -> str:
    """Une frame WS valide (``id`` + image), avec les knobs optionnels passés en ``extra``."""
    payload = {"id": ident, "image_b64": png}
    payload.update(extra)
    return json.dumps(payload)


def fake_pipeline(captured: dict | None = None):
    """Pipeline factice : renvoie la forme de ``POST /live`` et note ses arguments."""
    def run(image_b64, prompts, max_objects, work_edge, batch_prompts=None, edge=None):
        if captured is not None:
            captured.update(prompts=prompts, max_objects=max_objects, work_edge=work_edge,
                            batch_prompts=batch_prompts, edge=edge, image_len=len(image_b64))
        return {"width": 64, "height": 40, "elapsed_ms": 12, "objects": []}
    return run


def test_schema(schemas) -> None:
    """``LiveWsRequest`` : ``id`` et image obligatoires, leviers de ``/live`` acceptés."""
    ok = schemas.LiveWsRequest(id=3, image_b64="x" * 300)
    check("LiveWsRequest: id + image", ok.id == 3 and ok.prompts is None and ok.edge is None)
    check("LiveWsRequest: sans id → refus (422 côté validation)",
          raises(lambda: schemas.LiveWsRequest(image_b64="x" * 300), ValidationError))
    check("LiveWsRequest: sans image → refus",
          raises(lambda: schemas.LiveWsRequest(id=1), ValidationError))
    knobs = schemas.LiveWsRequest(id=1, image_b64="x" * 300, batch_prompts=1, edge=640)
    check("LiveWsRequest: batch_prompts/edge acceptés",
          knobs.batch_prompts == 1 and knobs.edge == 640)


def test_parse_frame(live_ws) -> None:
    """``parse_frame`` : l'``id`` est conservé dès qu'il est lisible, ``-1`` sinon."""
    ok = live_ws.parse_frame(json.dumps({"id": 9, "image_b64": "x" * 300}))
    check("parse_frame: frame valide", ok["error"] is None and ok["request"].id == 9)

    broken = live_ws.parse_frame("{pas du json")
    check("parse_frame: JSON cassé → id -1 + 400",
          broken["id"] == -1 and broken["error"]["status"] == 400, str(broken)[:160])

    no_id = live_ws.parse_frame(json.dumps({"image_b64": "x" * 300}))
    check("parse_frame: id absent → -1 + 400",
          no_id["id"] == -1 and no_id["error"]["status"] == 400 and "id" in no_id["error"]["detail"])

    bad_prompts = live_ws.parse_frame(json.dumps({"id": 5, "image_b64": "x" * 300, "prompts": "car"}))
    check("parse_frame: id conservé quand le reste est invalide",
          bad_prompts["id"] == 5 and bad_prompts["error"]["status"] == 400, str(bad_prompts)[:160])

    not_object = live_ws.parse_frame(json.dumps(["x" * 300]))
    check("parse_frame: corps non-objet → -1 + 400",
          not_object["id"] == -1 and not_object["error"]["status"] == 400)


def test_ws_contract(live_app) -> None:
    """Une frame valide → même réponse que ``POST /live``, plus l'``id`` ; knobs transmis."""
    service, api = _live_service(live_app)
    png = sample_png()
    captured: dict = {}
    service._live_sync = fake_pipeline(captured)

    sent = asyncio.run(ws_session(api, [frame(7, png, prompts=["person"], max_objects=4,
                                               work_edge=640)]))
    check("WS: connexion acceptée",
          bool(sent) and sent[0]["type"] == "websocket.accept", str(sent)[:120])
    answers = replies(sent)
    check("WS: une réponse par frame", len(answers) == 1, str(answers)[:200])
    check("WS: id corrélé", answers[0].get("id") == 7, str(answers[0])[:120])
    check("WS: clés = POST /live + id", set(answers[0]) == WS_KEYS, str(sorted(answers[0])))
    check("WS: knobs transmis au pipeline",
          captured == {"prompts": ["person"], "max_objects": 4, "work_edge": 640,
                       "batch_prompts": None, "edge": None, "image_len": len(png)}, str(captured))

    status, payload = asyncio.run(asgi_call(api, "POST", "/live", json.dumps(
        {"image_b64": png, "prompts": ["person"], "max_objects": 4,
         "work_edge": 640}).encode()))
    check("POST /live: forme LIVE-SPEC inchangée",
          status == 200 and set(payload) == LIVE_KEYS, str(payload)[:120])
    check("WS et POST /live: même structure de réponse",
          set(answers[0]) - {"id"} == set(payload), str(sorted(answers[0])))


def test_ws_errors(live_app) -> None:
    """Frame invalide ou pipeline en erreur → message d'erreur, connexion conservée."""
    service, api = _live_service(live_app)
    png = sample_png()
    service._live_sync = fake_pipeline()

    answers = replies(asyncio.run(ws_session(
        api, ["{pas du json", json.dumps({"image_b64": png}), frame(11, png)])))
    check("WS: une réponse par frame, même en erreur", len(answers) == 3, str(answers)[:200])
    check("WS: JSON cassé → id -1 + 400",
          answers[0].get("id") == -1 and answers[0]["error"]["status"] == 400, str(answers[0])[:160])
    check("WS: id absent → id -1 + 400",
          answers[1].get("id") == -1 and answers[1]["error"]["status"] == 400, str(answers[1])[:160])
    check("WS: la frame suivante passe (connexion gardée)",
          answers[2].get("id") == 11 and "objects" in answers[2], str(answers[2])[:160])

    def boom(*_args, **_kwargs):
        raise RuntimeError("gpu exploded")

    service._live_sync = boom
    answers = replies(asyncio.run(ws_session(api, [frame(21, png), frame(22, png)])))
    check("WS: pipeline en vrac → 500, id conservé, connexion gardée",
          len(answers) == 2 and answers[0]["id"] == 21
          and answers[0]["error"]["status"] == 500 and answers[1]["id"] == 22,
          str(answers)[:200])

    def bad_image(*_args, **_kwargs):
        from imaging import ImageDecodeError

        raise ImageDecodeError("invalid base64 payload")

    service._live_sync = bad_image
    answers = replies(asyncio.run(ws_session(api, [frame(31, png)])))
    check("WS: base64 illisible → 400 (même mapping que POST /live)",
          answers[0]["error"]["status"] == 400, str(answers)[:160])


def test_ws_binary(live_app) -> None:
    """Message binaire (hors contrat texte) → erreur 400, la connexion reste ouverte."""
    service, api = _live_service(live_app)
    png = sample_png()
    service._live_sync = fake_pipeline()
    answers = replies(asyncio.run(ws_session(api, [b"\x00\x01", frame(41, png)])))
    check("WS: binaire → erreur 400",
          len(answers) == 2 and answers[0]["error"]["status"] == 400, str(answers)[:160])
    check("WS: binaire puis texte → la connexion vit",
          answers[1].get("id") == 41, str(answers)[:160])


RUNNERS = (
    ("schéma LiveWsRequest", "schemas", test_schema),
    ("protocole WS (parse_frame)", "live_ws", test_parse_frame),
    ("WS: contrat de réponse", "live_app_fast", test_ws_contract),
    ("WS: erreurs sans fermeture", "live_app_fast", test_ws_errors),
    ("WS: message binaire", "live_app_fast", test_ws_binary),
)


def main() -> int:
    sys.path.insert(0, str(BACKEND_DIR))
    install_stubs()
    for title, module_name, runner in RUNNERS:
        try:
            runner(__import__(module_name))
        except Exception as exc:  # noqa: BLE001 - un groupe ne doit pas arrêter les autres
            FAILED.append(f"{title} — exception {type(exc).__name__}: {exc}")
    print(f"Tests /live/ws : {len(PASSED)} OK, {len(FAILED)} KO")
    for line in FAILED:
        print(f"  KO {line}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
