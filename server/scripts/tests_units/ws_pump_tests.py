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
        self.reasons: list[str] = []

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
        self.reasons.append(reason or "")


class _Service:
    """Doublure de ``Sam3LiveService`` : réponse fixe, appels comptés, barrière optionnelle.

    ``slots``/``frame_timeouts``/``ws`` sont ceux du vrai service (``__init__``) : les
    garde-fous (créneau rendu, chien de garde, compteurs) sont donc exercés pour de vrai.
    """

    def __init__(self, gate=None, reply=None, stuck=False) -> None:
        from gpu_guard import Slots

        self.calls: list = []
        self._gate = gate
        self._stuck = stuck
        self.reply = dict(reply or {"width": 64, "height": 40, "elapsed_ms": 1,
                                    "objects": []})
        self.slots = Slots()
        self.frame_timeouts = 0

    async def _run_live(self, request) -> dict:
        self.calls.append(request)
        if self._stuck:  # inférence qui ne finit jamais (aucune horloge de boucle liée)
            await asyncio.sleep(3600)
        elif self._gate is not None:
            await self._gate.wait()
        return dict(self.reply)

    async def _run_live_binary_ws(self, request, _jpeg) -> dict:
        return await self._run_live(request)


def _fresh(name: str):
    """Un module de ``live_fast/`` exécuté à neuf (aucune copie racine, cache intact)."""
    import importlib.util

    from test_units import BACKEND_DIR

    live_fast = BACKEND_DIR / "live_fast"
    sys.path.insert(0, str(live_fast))
    try:
        spec = importlib.util.spec_from_file_location(f"live_fast_{name}_probe",
                                                      live_fast / f"{name}.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    finally:
        sys.path.remove(str(live_fast))
    return module


def _fresh_pump():
    """``live_fast/ws_pump.py`` exécuté à neuf (aucune copie racine, cache intact)."""
    return _fresh("ws_pump")


class _KeepAliveSocket(_ScriptSocket):
    """Socket qui ping toutes les ``gap`` s : prouve que le keepalive réarme l'idle."""

    def __init__(self, gap, pings) -> None:
        super().__init__([])
        self._gap = gap
        self._pings = pings

    async def receive(self):
        if self._pings <= 0:
            return {"type": "websocket.disconnect"}
        self._pings -= 1
        await asyncio.sleep(self._gap)
        return {"type": "websocket.receive", "text": '{"id":0,"ping":1}'}


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
    """Ping servi puis silence : pong envoyé, idle 1001 ensuite (reset puis feu)."""
    from test_units import check

    service = _Service()
    socket = _ScriptSocket(['{"type":"ping","id":3}', _HANG])
    asyncio.run(pump.ws_loop(service, socket, 0.02, 5.0, 8))
    check("ping: pong envoyé",
          [json.loads(text) for text in socket.sent] == [{"id": 3, "pong": True}],
          str(socket.sent)[:120])
    check("ping: GPU intact", service.calls == [], str(service.calls))
    check("idle après ping → 1001 (going away, pas 1011)",
          socket.closes[:1] == [1001], str(socket.closes))


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


def test_health_ws_fields(_pump) -> None:
    """P1-3/P1-4 : ``/health`` cesse de mentir — ``ws_ready``, ``warm`` et compteurs."""
    from tests_units.ws_binary_tests import _live_fast_service
    from test_units import asgi_call, check

    service, api = _live_fast_service()
    service.ctx = {}
    _status, cold = asyncio.run(asgi_call(api, "GET", "/health", b""))
    check("health: modèle chargé → ok + ws_ready vrais",
          cold.get("ok") is True and cold.get("ws_ready") is True, str(cold)[:200])
    check("health: warm faux tant qu'aucune frame n'a été servie (P1-4)",
          cold.get("warm") is False, str(cold.get("warm")))
    check("health: compteurs du direct publiés (P1-3)",
          isinstance(cold.get("ws"), dict)
          and {"sessions", "open", "frames", "last_frame_age_s"} <= set(cold["ws"]),
          str(cold.get("ws"))[:200])

    service.warm = True
    service.frame_timeouts = 3  # chien de garde armé : la carte ne répond plus
    _status, stuck = asyncio.run(asgi_call(api, "GET", "/health", b""))
    check("health: carte bloquée → ok faux (fin du littéral)",
          stuck.get("ok") is False and stuck.get("ws_ready") is False, str(stuck)[:200])
    check("health: warm reste vrai (une frame a bien été servie)",
          stuck.get("warm") is True, str(stuck.get("warm")))


def test_keepalive_rearms_idle(pump) -> None:
    """P1-1 : le keepalive applicatif réarme le chrono — le ping protocole, lui, ne le fait pas."""
    from test_units import check

    service = _Service()
    socket = _KeepAliveSocket(gap=0.02, pings=5)
    asyncio.run(pump.ws_loop(service, socket, 0.05, 5.0, 8))
    check("keepalive: 5 pings à 20 ms sous un idle de 50 ms → aucune fermeture",
          socket.closes == [1000], str(socket.closes))
    check("keepalive: un pong par ping, forme courte acceptée",
          [json.loads(text) for text in socket.sent] == [{"id": 0, "pong": True}] * 5,
          str(socket.sent)[:160])
    check("keepalive: aucune inférence déclenchée", service.calls == [], str(service.calls))

    spaced = _KeepAliveSocket(gap=0.08, pings=5)
    asyncio.run(pump.ws_loop(_Service(), spaced, 0.05, 5.0, 8))
    check("keepalive: ping espacé au-delà de l'idle → fermeture 1001",
          spaced.closes[:1] == [1001], str(spaced.closes))
    check("keepalive: {'id':0,'ping':1} classé ping",
          pump.classify('{"id":0,"ping":1}') == ("ping", 0)
          and pump.classify('{"id":0,"ping":0}') == ("frame", 0))


def test_slots_released_all_paths(pump) -> None:
    """P0-1 : timeout, cancel, client parti — le créneau est rendu, jamais le slot."""
    from test_live_ws import frame
    from test_units import check, sample_png

    png = sample_png()
    cases = {
        "timeout": ([frame(1, png), _HANG], 0.02),
        "cancel": ([frame(1, png), '{"id":1,"cancel":true}'], 5.0),
        "client parti": ([frame(1, png), None], 5.0),
    }
    saved = pump.WS_ABANDON_SECONDS
    pump.WS_ABANDON_SECONDS = 0.01  # un pair parti n'attend pas 2 s pour rien
    try:
        for name, (script, frame_seconds) in cases.items():
            service = _Service(stuck=True)  # inférence qui ne finit jamais
            asyncio.run(pump.ws_loop(service, _ScriptSocket(script), 0.02,
                                     frame_seconds, 8))
            slots = service.slots.snapshot()
            check(f"P0-1 {name}: créneau rendu (active=0)", slots["active"] == 0, str(slots))
            check(f"P0-1 {name}: abandon compté", slots["abandoned"] == 1, str(slots))
    finally:
        pump.WS_ABANDON_SECONDS = saved


def test_busy_maps_429(_module) -> None:
    """P0-1/P2-2 : attente au verrou bornée → 429 « occupé », pas 503 « lent »."""
    import threading

    import gpu_guard
    from fastapi import HTTPException
    from test_units import check

    pipe = _fresh("pipe_compat")
    core = _fresh("fast_core")

    def busy():
        raise gpu_guard.GpuBusy("gpu busy > 15 s")

    try:
        asyncio.run(pipe.call(busy))
    except HTTPException as exc:
        check("busy → 429 borné", exc.status_code == 429 and len(exc.detail) <= 300,
              f"{exc.status_code} {exc.detail}")
    else:
        check("busy → 429 borné", False, "aucune exception")

    lock = threading.Lock()
    lock.acquire()  # une frame tient la carte
    try:
        with core._locked(lock, {}, timeout=0.01):
            check("verrou pris: GpuBusy levée", False, "verrou obtenu")
    except gpu_guard.GpuBusy:
        check("verrou pris au-delà du budget → GpuBusy", True)
    finally:
        lock.release()
    with core._locked(lock, {}, timeout=0.5):
        pass
    check("verrou rendu: ré-acquérable", True)
    check("budget d'attente par défaut = 15 s", gpu_guard.GPU_QUEUE_SECONDS == 15.0,
          str(gpu_guard.GPU_QUEUE_SECONDS))


def test_ws_oversize_degrades(pump) -> None:
    """P2-1 : réponse trop grosse → réponse dégradée ; 1009 seulement en dernier recours."""
    from test_live_ws import frame
    from test_units import check, sample_png

    png = sample_png()
    fat = [{"label": "person", "score": 0.9, "box": [1, 2, 3, 4], "mask_b64": "x" * 400}
           for _ in range(4)]
    saved = pump.WS_MAX_REPLY_BYTES
    pump.WS_MAX_REPLY_BYTES = 600
    try:
        service = _Service(reply={"width": 64, "height": 40, "elapsed_ms": 1,
                                  "objects": fat})
        socket = _ScriptSocket([frame(9, png), None])
        asyncio.run(pump.ws_loop(service, socket, 5.0, 5.0, 8))
        answers = [json.loads(text) for text in socket.sent]
        check("1009: la session survit (aucune fermeture 1009)",
              socket.closes == [1000], str(socket.closes))
        check("1009: réponse dégradée envoyée (moitié des objets)",
              len(answers) == 1 and len(answers[0]["objects"]) == 2, str(answers)[:200])
        check("1009: masques retirés + mode d'emploi dans la trame",
              all("mask_b64" not in obj for obj in answers[0]["objects"])
              and answers[0]["mask_encoding"] == "none"
              and answers[0]["degraded"]["max_objects"] == 2,
              str(answers[0])[:200])
        check("1009: compteur de dégradation", service.ws["degraded"] == 1,
              str(service.ws))

        blocked = [{"label": "x" * 800, "score": 0.9, "box": [1, 2, 3, 4]}]
        service = _Service(reply={"width": 64, "height": 40, "elapsed_ms": 1,
                                  "objects": blocked})
        socket = _ScriptSocket([frame(10, png), None])
        asyncio.run(pump.ws_loop(service, socket, 5.0, 5.0, 8))
        check("1009: indégradable → fermeture 1009",
              socket.closes[:1] == [1009], str(socket.closes))
        check("1009: reason exploitable par le client",
              "max_objects" in (socket.reasons[0] if socket.reasons else ""),
              str(socket.reasons))
        check("1009: rien d'envoyé pour une réponse indégradable",
              socket.sent == [], str(socket.sent)[:120])
    finally:
        pump.WS_MAX_REPLY_BYTES = saved


def test_watchdog_restarts(pump) -> None:
    """Chien de garde : 3 frames muettes consécutives → redémarrage ; 2 → simple 503."""
    import gpu_guard
    from test_live_ws import frame
    from test_units import check, sample_png

    png = sample_png()
    restarts: list = []
    saved = gpu_guard.restart_later
    gpu_guard.restart_later = lambda *args, **kwargs: restarts.append(1)
    try:
        service = _Service(stuck=True)  # l'inférence ne finit jamais
        for _ in range(2):
            socket = _ScriptSocket([frame(1, png), _HANG])
            asyncio.run(pump.ws_loop(service, socket, 0.02, 0.02, 8))
            check("timeout: 503 servi, connexion gardée",
                  json.loads(socket.sent[0])["error"]["status"] == 503,
                  str(socket.sent)[:160])
        check("chien de garde: 2 timeouts → pas de redémarrage",
              restarts == [] and service.frame_timeouts == 2, str(service.frame_timeouts))
        asyncio.run(pump.ws_loop(service, _ScriptSocket([frame(2, png), _HANG]),
                                 0.02, 0.02, 8))
        check("chien de garde: 3e timeout → redémarrage planifié",
              restarts == [1] and service.frame_timeouts == 3, str(restarts))
        gpu_guard.note_success(service)
        check("chien de garde: une frame servie remet le compteur à zéro",
              service.frame_timeouts == 0, str(service.frame_timeouts))
        check("chien de garde: 3 = seuil", gpu_guard.WEDGE_RESTART_TIMEOUTS == 3,
              str(gpu_guard.WEDGE_RESTART_TIMEOUTS))
    finally:
        gpu_guard.restart_later = saved


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
    test_health_ws_fields(pump)
    test_mixed_ping_binary(pump)
    test_keepalive_rearms_idle(pump)
    test_slots_released_all_paths(pump)
    test_busy_maps_429(pump)
    test_ws_oversize_degrades(pump)
    test_watchdog_restarts(pump)
