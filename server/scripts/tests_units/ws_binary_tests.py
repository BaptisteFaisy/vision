"""WS ``/live/ws`` binaire M1, de bout en bout sur le service déployé (``live_fast``).

Sans GPU ni réseau : l'app ASGI de ``Sam3LiveService`` est pilotée à la main
(même harnais que ``test_live_ws.py``), le pipeline remplacé par un stub. Trame
valide servie (JPEG brut décodé, knobs transmis), mixité texte/binaire sur un
même socket, trames limites en erreur bornée sans fermer la connexion.
"""

from __future__ import annotations

import asyncio
import base64
import json
import struct
import sys
import threading


def _live_fast_service():
    """``Sam3LiveService`` + app FastAPI, hors GPU (import isolé du fork déployé).

    ``live_fast/`` est un fork (``fast_core`` diffère de ``backend/``) : on
    l'importe en tête de ``sys.path`` après avoir purgé les modules dupliqués,
    puis on restaure le ``sys.path`` et les modules de ``backend/`` — le service
    garde ses références liées. ``live_fast/`` reste en queue de ``sys.path``
    pour les modules qui n'existent que là-bas (``feux_guard``…).
    """
    from test_units import BACKEND_DIR

    live_fast = BACKEND_DIR / "live_fast"
    if str(live_fast) not in sys.path:
        sys.path.append(str(live_fast))
    sys.path.insert(0, str(live_fast))
    purged = {name: sys.modules.pop(name, None) for name in
              ("service", "detr_graph", "fast_core", "http_errors", "passage",
               "timing", "token_cache", "weights", "concepts")}
    try:
        import service as deployed
    finally:
        sys.path.remove(str(live_fast))
        for name, module in purged.items():
            if module is not None:
                sys.modules[name] = module
    service = deployed.Sam3LiveService()
    service._gpu_lock = threading.Lock()
    service.gpu_name = "NVIDIA A100 80GB PCIe"
    return service, service.api()


def _binary(ident: int, jpeg: bytes, **extra) -> bytes:
    """Une trame WS binaire M1 (en-tête sans image + JPEG brut)."""
    header = json.dumps({"id": ident, **extra}).encode()
    return struct.pack(">I", len(header)) + header + jpeg


def test_ws_binary_frame(_) -> None:
    """Trame binaire valide → même réponse que le texte, JPEG brut décodé."""
    from test_live_ws import replies, ws_session
    from test_units import check, sample_png

    service, api = _live_fast_service()
    captured: dict = {}

    def from_image(image, prompts, max_objects, work_edge, batch_prompts, edge,
                   mask_encoding, mask_format, mask_edge, timers, started, with_depth=False):
        captured.update(size=image.size, prompts=prompts, max_objects=max_objects,
                        work_edge=work_edge, mask_encoding=mask_encoding,
                        mask_format=mask_format, mask_edge=mask_edge, with_depth=with_depth)
        return {"width": 64, "height": 40, "elapsed_ms": 12, "objects": [],
                "mask_encoding": mask_encoding or "png"}

    service._live_from_image = from_image
    raw = base64.b64decode(sample_png())
    answers = replies(asyncio.run(ws_session(api, [_binary(
        7, raw, prompts=["person"], work_edge=640, max_objects=4, mask_encoding="bits")])))
    check("WS binaire: une réponse", len(answers) == 1, str(answers)[:160])
    check("WS binaire: id corrélé + forme POST /live",
          answers[0].get("id") == 7 and {"width", "height", "elapsed_ms", "objects"} <= set(answers[0]),
          str(sorted(answers[0])))
    check("WS binaire: JPEG brut décodé (64x40)",
          captured.get("size") == (64, 40), str(captured.get("size")))
    check("WS binaire: knobs transmis",
          captured.get("prompts") == ["person"] and captured.get("work_edge") == 640
          and captured.get("max_objects") == 4 and captured.get("mask_encoding") == "bits",
          str(captured))


def test_ws_binary_mixed(_) -> None:
    """Texte et binaire alternés sur un socket : ``id`` pour ``id``, sans fermeture."""
    from test_live_ws import frame, replies, ws_session
    from test_units import check, sample_png

    service, api = _live_fast_service()
    service._live_sync = lambda *a, **k: {"width": 64, "height": 40, "elapsed_ms": 12,
                                          "objects": []}
    service._live_from_image = lambda *a, **k: {"width": 64, "height": 40, "elapsed_ms": 12,
                                                "objects": []}
    png = sample_png()
    raw = base64.b64decode(png)
    answers = replies(asyncio.run(ws_session(
        api, [frame(1, png), _binary(2, raw), frame(3, png), _binary(4, raw)])))
    check("WS mixte: 4 réponses", len(answers) == 4, str(answers)[:200])
    check("WS mixte: id pour id",
          [answer.get("id") for answer in answers] == [1, 2, 3, 4], str(answers)[:200])
    check("WS mixte: aucune erreur",
          all("error" not in answer for answer in answers), str(answers)[:200])


def test_ws_binary_errors(_) -> None:
    """Trames binaires limites → erreur bornée avec l'id, connexion maintenue."""
    from test_live_ws import replies, ws_session
    from test_units import check, sample_png

    service, api = _live_fast_service()
    service._live_from_image = lambda *a, **k: {"width": 1, "height": 1, "elapsed_ms": 1,
                                                "objects": []}
    raw = base64.b64decode(sample_png())
    answers = replies(asyncio.run(ws_session(api, [
        struct.pack(">I", 5) + b"{oops" + raw, struct.pack(">I", 0) + raw,
        _binary(13, b""), _binary(14, b"pas une image"), _binary(15, raw)])))
    check("WS binaire: 5 réponses", len(answers) == 5, str(answers)[:200])
    check("WS binaire: en-tête corrompu → id -1 + 400 borné",
          answers[0].get("id") == -1 and answers[0]["error"]["status"] == 400
          and len(answers[0]["error"]["detail"]) <= 300, str(answers[0])[:160])
    check("WS binaire: N=0 → id -1 + 400",
          answers[1].get("id") == -1 and answers[1]["error"]["status"] == 400,
          str(answers[1])[:160])
    check("WS binaire: JPEG vide → id conservé + 400",
          answers[2].get("id") == 13 and answers[2]["error"]["status"] == 400,
          str(answers[2])[:160])
    check("WS binaire: JPEG indécodable → id conservé + 400",
          answers[3].get("id") == 14 and answers[3]["error"]["status"] == 400,
          str(answers[3])[:160])
    check("WS binaire: la frame suivante passe (connexion gardée)",
          answers[4].get("id") == 15 and "error" not in answers[4], str(answers[4])[:160])
