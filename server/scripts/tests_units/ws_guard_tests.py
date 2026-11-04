"""Garde-fous anti-coinçage (M1) + réception WS instrumentée (M2), sans GPU ni réseau.

Chaque borne ajoutée pour que plus aucun input Modal ne parque jusqu'au timeout
(1 h) est exercée ici contre une doublure : ramassage double-buffer, garde POST
+ watchdog, handshake, attente verrou, réception binaire/illisible.
"""

from __future__ import annotations

import asyncio
import threading
import time
from types import SimpleNamespace

from tests_units.ws_pump_tests import _Service, _fresh


class _DeafSocket:
    """Socket dont la réception ignore UNE annulation (waiter transport sourd)."""

    def __init__(self) -> None:
        self.sent: list[str] = []
        self.closes: list[int] = []

    async def receive(self):
        await asyncio.shield(asyncio.sleep(0.2))
        return {"type": "websocket.disconnect"}

    async def send_text(self, text) -> None:
        self.sent.append(text)

    async def close(self, code=1000, reason=None) -> None:
        self.closes.append(code)


class _SleepSocket:
    """Socket dont le handshake dort (client qui dégouline)."""

    def __init__(self, seconds: float) -> None:
        self._seconds = seconds

    async def accept(self) -> None:
        await asyncio.sleep(self._seconds)


def test_gather_bounded(_module) -> None:
    """Ramassage parqué : la session revient même si le waiter ignore cancel."""
    from test_units import check

    async def scenario():
        import json as _json

        from test_units import sample_png

        pump = _fresh("ws_pump")
        pump.WS_CANCEL_SECONDS = 0.05
        service = _Service(stuck=True)
        raw = _json.dumps({"id": 7, "image_b64": sample_png()})
        started = time.monotonic()
        reply, _held, _stop = await pump._infer_with_prefetch(
            service, _DeafSocket(), raw, None, 8, 0.1)
        elapsed = time.monotonic() - started
        await asyncio.sleep(0.3)  # le waiter sourd finit seul (0,2 s)
        return reply, elapsed

    reply, elapsed = asyncio.run(scenario())
    check("ramassage : session revenue malgré waiter sourd", elapsed < 2.0, f"{elapsed:.2f} s")
    check("ramassage : 503 timeout, pas de 500", reply.get("error", {}).get("status") == 503,
          str(reply)[:80])


def test_post_guarded(_module) -> None:
    """Garde POST : 503 + watchdog sur coro pendue, succès sinon."""
    from test_units import check

    async def scenario():
        guard = _fresh("gpu_guard")
        service = SimpleNamespace(slots=guard.Slots(), frame_timeouts=0)

        async def stuck() -> None:
            await asyncio.sleep(3600)

        try:
            await guard.post_guarded(service, stuck(), frame_seconds=0.05)
            timeout: str | None = None
        except Exception as exc:  # noqa: BLE001 — on vérifie le statut exact
            timeout = f"{getattr(exc, 'status_code', '?')}"
        abandoned = service.slots.abandoned
        timeouts = service.frame_timeouts
        service.frame_timeouts = 2
        ok = await guard.post_guarded(service, _fast())
        return timeout, abandoned, timeouts, ok, service.frame_timeouts

    async def _fast() -> str:
        return "servi"

    timeout, abandoned, timeouts, ok, reset = asyncio.run(scenario())
    check("post garde : coro pendue -> 503", timeout == "503", str(timeout))
    check("post garde : slot abandonne compte", abandoned == 1, str(abandoned))
    check("post garde : watchdog compte", timeouts == 1, str(timeouts))
    check("post garde : succes servi + compteur remis a zero", ok == "servi" and reset == 0,
          f"{ok} / {reset}")


def test_accept_bounded(_module) -> None:
    """Handshake qui dégouline : False rapide, 0 session."""
    from test_units import check

    async def scenario():
        session = _fresh("ws_session")
        session.WS_ACCEPT_SECONDS = 0.05
        started = time.monotonic()
        refused = await session.accept_ws(_SleepSocket(3600))

        async def _accept_now() -> None:
            return None

        quick = SimpleNamespace(accept=_accept_now)
        accepted = await session.accept_ws(quick)
        return refused, accepted, time.monotonic() - started

    refused, accepted, elapsed = asyncio.run(scenario())
    check("accept borne : degoulinant refuse", refused is False, str(refused))
    check("accept borne : sain accepte", accepted is True, str(accepted))
    check("accept borne : rapide", elapsed < 2.0, f"{elapsed:.2f} s")


def test_acquire_bounded(_module) -> None:
    """Attente verrou : tenu -> False rapide, libre/None -> True."""
    from test_units import check

    guard = _fresh("gpu_guard")
    guard.GPU_QUEUE_SECONDS = 0.05
    lock = threading.Lock()
    lock.acquire()
    try:
        started = time.monotonic()
        held = guard.acquire(lock)
        elapsed = time.monotonic() - started
    finally:
        lock.release()
    check("acquire borne : verrou tenu -> False", held is False, str(held))
    check("acquire borne : rapide", elapsed < 2.0, f"{elapsed:.2f} s")
    check("acquire borne : verrou libre -> True", guard.acquire(lock) is True)
    check("acquire borne : pas de verrou -> True", guard.acquire(None) is True)


def _recv_probe():
    """Quatre réceptions scriptées : binaire, texte forme-Modal, binaire Modal, boom."""

    async def scenario():
        import contextlib
        import io

        pump = _fresh("ws_pump")

        class _Bin:
            async def receive(self):
                return {"type": "websocket.receive", "bytes": b"\x00\x01bin"}

        class _ModalText:
            # Forme Modal : les DEUX clés, `bytes` à None — a tué des sessions.
            async def receive(self):
                return {"type": "websocket.receive", "text": '{"id": 3}',
                        "bytes": None}

        class _ModalBin:
            async def receive(self):
                return {"type": "websocket.receive", "text": None,
                        "bytes": b"\x00\x01bin"}

        class _Boom:
            async def receive(self):
                raise ConnectionError("transport coupe")

        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            results = [await pump._recv_one(sock())
                       for sock in (_Bin, _ModalText, _ModalBin, _Boom)]
        return results, out.getvalue()

    return asyncio.run(scenario())


def test_recv_binary_logged(_module) -> None:
    """Réception : binaire intact, formes Modal livrées, journal présent."""
    from test_units import check

    results, logs = _recv_probe()
    (kind_bin, raw_bin), (kind_txt, raw_txt), (kind_mbin, raw_mbin), _boom = results
    check("recv : binaire livre intact", (kind_bin, raw_bin) == ("msg", b"\x00\x01bin"),
          f"{kind_bin} {raw_bin!r}")
    check("recv : forme Modal texte (bytes None) ne leve pas",
          (kind_txt, raw_txt) == ("msg", '{"id": 3}'), f"{kind_txt} {raw_txt!r}")
    check("recv : forme Modal binaire livre les octets",
          (kind_mbin, raw_mbin) == ("msg", b"\x00\x01bin"), f"{kind_mbin} {raw_mbin!r}")
    check("recv : message binaire journalise", "message binaire reçu (5 o)" in logs, logs[:120])


def test_recv_error_logged(_module) -> None:
    """Réception : l'exception transport est imprimée, connexion gardée."""
    from test_units import check

    results, logs = _recv_probe()
    kind_boom, raw_boom = results[-1]
    check("recv : exception -> 400, connexion gardee", (kind_boom, raw_boom) == ("msg", None),
          f"{kind_boom} {raw_boom!r}")
    check("recv : exception imprimee avec son type", "receive() a levé ConnectionError" in logs,
          logs[:120])


def test_ws_guard(_module) -> None:
    """Entrée du runner : bornes M1 + réception instrumentée M2."""
    import sys

    from test_units import BACKEND_DIR

    live_fast = str(BACKEND_DIR / "live_fast")
    if live_fast not in sys.path:
        sys.path.append(live_fast)
    test_gather_bounded(_module)
    test_post_guarded(_module)
    test_accept_bounded(_module)
    test_acquire_bounded(_module)
    test_recv_error_logged(_module)
    test_recv_binary_logged(_module)
