"""Pompe WS déployée (``live_fast/ws_pump``) : double-buffer, ping, cancel, health.

Sans GPU ni réseau : ``ws_pump`` est exécuté du fork déployé (même motif que
``feux_guard_tests``, sans polluer le cache des modules), le service est une
doublure (``_run_live*`` à appel compté ou à barrière), le socket un script
(messages Starlette). Le niveau ASGI réutilise ``_live_fast_service`` et
``ws_session`` (zéro duplication) : ping + binaire mixtes, ``/health`` enrichi.
"""

from __future__ import annotations

import asyncio
import json
import sys
from types import SimpleNamespace

_HANG = object()  # réception qui ne répond jamais (teste l'idle / borne un waiter)


class _ScriptSocket:
    """Socket Starlette scripté : str/bytes → message, None → disconnect, _HANG → silence."""

    def __init__(self, script) -> None:
        self._script = list(script)
        self.sent: list[str] = []
        self.closes: list[int] = []

    async def receive(self):
        if not self._script:
            return {"type": "websocket.disconnect"}
        item = self._script.pop(0)
        if item is _HANG:
            await asyncio.sleep(3600)
            raise AssertionError("unreachable")
        if item is None:
            return {"type": "websocket.disconnect"}
        key = "bytes" if isinstance(item, (bytes, bytearray)) else "text"
        return {"type": "websocket.receive", key: item}

    async def send_text(self, text) -> None:
        self.sent.append(text)

    async def close(self, code=1000, reason=None) -> None:
        self.closes.append(code)


class _Service:
    """Doublure de ``Sam3LiveService`` : réponse fixe, appels comptés, barrière optionnelle."""

    def __init__(self, gate=None) -> None:
        self.calls: list = []
        self._gate = gate

    async def _run_live(self, request) -> dict:
        self.calls.append(request)
        if self._gate is not None:
            await self._gate.wait()
        return {"width": 64, "height": 40, "elapsed_ms": 1, "objects": []}

    async def _run_live_binary_ws(self, request, _jpeg) -> dict:
        return await self._run_live(request)


def _fresh_pump():
    """``live_fast/ws_pump.py`` exécuté à neuf (aucune copie racine, cache intact)."""
    import importlib.util

    from test_units import BACKEND_DIR

    live_fast = BACKEND_DIR / "live_fast"
    sys.path.insert(0, str(live_fast))
    try:
        spec = importlib.util.spec_from_file_location("live_fast_ws_pump_probe",
                                                      live_fast / "ws_pump.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    finally:
        sys.path.remove(str(live_fast))
    return module


def _gated(pump, first, raw):
    """Pré-lecture à barrière : ``first`` consommé pendant que N attend, puis N finit."""

    async def scenario():
        gate = asyncio.Event()
        service = _Service(gate)
        socket = _ScriptSocket([first, _HANG])
        task = asyncio.create_task(
            pump._infer_with_prefetch(service, socket, raw, None, 8, 5.0))
        await asyncio.sleep(0.05)  # le waiter consomme first, N reste bloquée
        gate.set()
        return await task, socket

    return asyncio.run(scenario())


def test_classify(pump) -> None:
    """Routage des messages : ping / cancel / frame, binaire et illisible = frame."""
    from test_units import check

    cls = pump.classify
    check("classify: ping avec id", cls('{"type":"ping","id":9}') == ("ping", 9))
    check("classify: ping sans id → -1", cls('{"type":"ping"}') == ("ping", -1))
    check("classify: cancel avec id", cls('{"id":7,"cancel":true}') == ("cancel", 7))
    check("classify: cancel sans id", cls('{"cancel":true}') == ("cancel", -1))
    check("classify: frame", cls('{"id":7,"image_b64":"x"}') == ("frame", 7))
    check("classify: binaire = frame", cls(b"\x00\x01") == ("frame", -1))
    check("classify: JSON cassé = frame", cls("{oops") == ("frame", -1))
    check("classify: non-objet = frame", cls("[1,2]") == ("frame", -1))
    check("classify: cancel=false = frame",
          cls('{"id":7,"cancel":false}') == ("frame", 7))
    check("ping_reply: pong", pump.ping_reply('{"type":"ping","id":9}') == {"id": 9, "pong": True})
    check("ping_reply: non-ping → None", pump.ping_reply('{"id":7}') is None)
    check("vol borné à 2 (1 inférence + 1 message tenu)", pump.WS_MAX_IN_FLIGHT == 2)


def test_ping_no_gpu(pump) -> None:
    """Ping applicatif (FIX-17) : pong immédiat, GPU jamais touché."""
    from test_units import check

    def boom(_request):
        raise AssertionError("GPU touché par un ping")

    service = SimpleNamespace(_run_live=boom, _run_live_binary_ws=boom)
    pong = asyncio.run(pump.ws_frame(service, '{"type":"ping","id":9}'))
    check("ping: pong sans GPU", pong == {"id": 9, "pong": True}, str(pong))


def test_loop_ping_idle(pump) -> None:
    """Ping servi puis silence : pong envoyé, idle 1011 ensuite (reset puis feu)."""
    from test_units import check

    service = _Service()
    socket = _ScriptSocket(['{"type":"ping","id":3}', _HANG])
    asyncio.run(pump.ws_loop(service, socket, 0.02, 5.0, 8))
    check("ping: pong envoyé",
          [json.loads(text) for text in socket.sent] == [{"id": 3, "pong": True}],
          str(socket.sent)[:120])
    check("ping: GPU intact", service.calls == [], str(service.calls))
    check("idle après ping → 1011", socket.closes[:1] == [1011], str(socket.closes))


def test_loop_two_frames_order(pump) -> None:
    """Deux frames : deux réponses dans l'ordre, bye propre (FIX-13)."""
    from test_live_ws import frame
    from test_units import check, sample_png

    png = sample_png()
    service = _Service()
    socket = _ScriptSocket([frame(1, png), frame(2, png), None])
    asyncio.run(pump.ws_loop(service, socket, 5.0, 5.0, 8))
    answers = [json.loads(text) for text in socket.sent]
    check("ordre: deux réponses id pour id",
          [answer.get("id") for answer in answers] == [1, 2], str(answers)[:200])
    check("ordre: aucune erreur",
          all("error" not in answer for answer in answers), str(answers)[:200])
    check("ordre: bye propre", socket.closes == [1000], str(socket.closes))


def test_prefetch_holds_next(pump) -> None:
    """N lente + N+1 déjà arrivée : N répond, N+1 tenue (double-buffer FIX-13)."""
    from test_live_ws import frame
    from test_units import check, sample_png

    png = sample_png()
    second = frame(2, png)
    (reply, held, stop), _socket = _gated(pump, second, frame(1, png))
    check("pré-lecture: N répond id 1", (reply or {}).get("id") == 1, str(reply)[:160])
    check("pré-lecture: N+1 tenue", held == ("msg", second), str(held)[:120])
    check("pré-lecture: connexion gardée", stop is False, str(stop))


def test_cancel_kills_inflight(pump) -> None:
    """Cancel de N (avec ou sans id) : N tuée, 0 réponse, connexion gardée (FIX-8)."""
    from test_live_ws import frame
    from test_units import check, sample_png

    png = sample_png()
    for cancel in ('{"id":7,"cancel":true}', '{"cancel":true}'):
        (reply, held, stop), _socket = _gated(pump, cancel, frame(7, png))
        check(f"cancel {cancel}: 0 réponse", reply is None and held is None,
              f"{reply} {held}")
        check(f"cancel {cancel}: connexion gardée", stop is False, str(stop))


def test_cancel_stale_ignored(pump) -> None:
    """Cancel d'une autre frame : ignoré, N répond normalement."""
    from test_live_ws import frame
    from test_units import check, sample_png

    (reply, held, stop), _socket = _gated(pump, '{"id":99,"cancel":true}',
                                          frame(7, sample_png()))
    check("cancel périmé: N répond", (reply or {}).get("id") == 7, str(reply)[:160])
    check("cancel périmé: rien tenu, connexion gardée",
          held is None and stop is False, f"{held} {stop}")


def test_ping_during_inflight(pump) -> None:
    """Ping pendant N : pong immédiat, N continue et répond."""
    from test_live_ws import frame
    from test_units import check, sample_png

    (reply, _held, _stop), socket = _gated(pump, '{"type":"ping","id":5}',
                                           frame(7, sample_png()))
    check("ping en vol: pong immédiat",
          [json.loads(text) for text in socket.sent] == [{"id": 5, "pong": True}],
          str(socket.sent)[:120])
    check("ping en vol: N répond", (reply or {}).get("id") == 7, str(reply)[:160])


def test_health_ws_binary_sha(_pump) -> None:
    """GET /health : bit ws_binary + sha/commit du déployé (capacité FIX-4)."""
    from tests_units.ws_binary_tests import _live_fast_service
    from test_units import asgi_call, check

    service, api = _live_fast_service()
    service.ctx = {}
    status, payload = asyncio.run(asgi_call(api, "GET", "/health", b""))
    check("health: 200", status == 200, str(status))
    check("health: ws_binary", payload.get("ws_binary") is True, str(payload)[:200])
    check("health: sha + commit",
          isinstance(payload.get("sha"), str) and payload.get("commit") == payload.get("sha"),
          str(payload)[:200])


def test_mixed_ping_binary(_pump) -> None:
    """Ping puis binaire M1 sur un socket déployé : pong puis réponse, sans fermeture."""
    import base64

    from test_live_ws import binary_frame, replies, ws_session
    from tests_units.ws_binary_tests import _live_fast_service
    from test_units import check, sample_png

    service, api = _live_fast_service()
    service._live_from_image = lambda *a, **k: {"width": 64, "height": 40, "elapsed_ms": 12,
                                                "objects": []}
    raw = base64.b64decode(sample_png())
    answers = replies(asyncio.run(ws_session(
        api, ['{"type":"ping","id":2}', binary_frame(1, raw)])))
    check("mixte: pong puis réponse",
          [answer.get("id") for answer in answers] == [2, 1], str(answers)[:200])
    check("mixte: pong exact", answers[0] == {"id": 2, "pong": True}, str(answers[0]))
    check("mixte: binaire servi sans erreur",
          answers[1].get("id") == 1 and "error" not in answers[1], str(answers[1])[:160])


def test_ws_pump(_module) -> None:
    """Pompe WS déployée : routage, ping sans GPU, cancel, double-buffer, health."""
    pump = _fresh_pump()
    test_classify(pump)
    test_ping_no_gpu(pump)
    test_loop_ping_idle(pump)
    test_loop_two_frames_order(pump)
    test_prefetch_holds_next(pump)
    test_cancel_kills_inflight(pump)
    test_cancel_stale_ignored(pump)
    test_ping_during_inflight(pump)
    test_health_ws_binary_sha(pump)
    test_mixed_ping_binary(pump)
