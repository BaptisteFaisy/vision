"""Envois WS bornés (P0-2) : un pair parti ne parque plus la session.

Sans borne, un ``send_text`` sur un socket mort ne revient jamais : la session
reste « open », l'idle (qui ne surveille que ``receive()``) ne la ferme pas, et
le créneau d'entrée Modal reste occupé — à 4 sockets parqués, ``/health`` et
``/live`` font la queue au proxy sans fin. Ces tests prouvent la borne (5 s en
prod, centièmes ici) sur réponse, pong et fermeture, et la sortie de boucle.
"""

from __future__ import annotations

import asyncio
import time

from tests_units.ws_pump_tests import _ScriptSocket, _Service, _fresh

_BOUND = 0.02  # borne de test : un pair vivant répond en millisecondes


class _StuckSendSocket(_ScriptSocket):
    """Pair parti : la réception suit le script, tout envoi ne revient jamais."""

    async def send_text(self, text) -> None:
        await asyncio.sleep(3600)
        raise AssertionError("unreachable")

    async def close(self, code=1000, reason=None) -> None:
        await asyncio.sleep(3600)
        raise AssertionError("unreachable")


class _DeadSocket(_ScriptSocket):
    """Socket déjà fermé : tout envoi lève aussitôt (pas de parcage)."""

    async def send_text(self, text) -> None:
        raise RuntimeError("socket fermé")


def test_send_ok(session) -> None:
    """Pair vivant : envoi direct, motif ``None`` (la session continue)."""
    from test_units import check

    service, socket = _Service(), _ScriptSocket([])
    reply = {"id": 1, "objects": []}
    reason = asyncio.run(session.send_reply(service, socket, "{}", reply, 4_000_000))
    check("envoi vivant: motif None", reason is None, str(reason))
    check("envoi vivant: texte parti", len(socket.sent) == 1, str(socket.sent)[:80])


def test_send_stuck_returns_client(session) -> None:
    """Envoi parqué : ``\"client\"`` en ~borne, pas en 75 s ni jamais."""
    from test_units import check

    service, socket = _Service(), _StuckSendSocket([])
    started = time.monotonic()
    reason = asyncio.run(
        session.send_reply(service, socket, "{}", {"id": 1}, 4_000_000, _BOUND))
    elapsed = time.monotonic() - started
    check("envoi parqué: motif client", reason == "client", str(reason))
    check("envoi parqué: sorti en ~borne", elapsed < 2.0, f"{elapsed:.2f} s")


def test_send_error_returns_client(session) -> None:
    """Envoi qui lève : ``\"client\"`` aussitôt (socket déjà fermé)."""
    from test_units import check

    service, socket = _Service(), _DeadSocket([])
    reason = asyncio.run(
        session.send_reply(service, socket, "{}", {"id": 1}, 4_000_000, _BOUND))
    check("envoi en erreur: motif client", reason == "client", str(reason))


def test_close_stuck_bounded(session) -> None:
    """Fermeture parquée : ``False`` en ~borne (pas d'attente infinie)."""
    from test_units import check

    started = time.monotonic()
    done = asyncio.run(session.close_bounded(_StuckSendSocket([]), seconds=_BOUND))
    elapsed = time.monotonic() - started
    check("close parqué: False", done is False, str(done))
    check("close parqué: sorti en ~borne", elapsed < 2.0, f"{elapsed:.2f} s")


def test_pong_stuck_returns_false(pump) -> None:
    """Pong vers un pair parti : ``False`` en ~borne (borne prod injectée)."""
    from test_units import check

    import ws_session

    saved = ws_session.WS_SEND_SECONDS
    ws_session.WS_SEND_SECONDS = _BOUND
    try:
        started = time.monotonic()
        alive = asyncio.run(pump._pong(_StuckSendSocket([]), '{"id":0,"ping":1}'))
        elapsed = time.monotonic() - started
    finally:
        ws_session.WS_SEND_SECONDS = saved
    check("pong parqué: False", alive is False, str(alive))
    check("pong parqué: sorti en ~borne", elapsed < 2.0, f"{elapsed:.2f} s")


def test_loop_closes_on_stuck_send(pump) -> None:
    """Boucle + envoi parqué : session fermée, compteur ``open`` à zéro."""
    from test_live_ws import frame
    from test_units import check, sample_png

    import ws_session

    saved = ws_session.WS_SEND_SECONDS
    ws_session.WS_SEND_SECONDS = _BOUND
    try:
        service = _Service()
        socket = _StuckSendSocket([frame(1, sample_png()), None])
        started = time.monotonic()
        asyncio.run(pump.ws_loop(service, socket, 5.0, 5.0, 8))
        elapsed = time.monotonic() - started
    finally:
        ws_session.WS_SEND_SECONDS = saved
    check("boucle parquée: open retombe à 0",
          service.ws["open"] == 0, str(service.ws))
    check("boucle parquée: motif client compté",
          service.ws.get("close_client") == 1, str(service.ws))
    check("boucle parquée: sortie en ~borne, pas en 75 s",
          elapsed < 2.0, f"{elapsed:.2f} s")


def test_ws_send(_module) -> None:
    """Envois WS bornés : réponse, pong et fermeture rendent la session."""
    session = _fresh("ws_session")
    pump = _fresh("ws_pump")
    test_send_ok(session)
    test_send_stuck_returns_client(session)
    test_send_error_returns_client(session)
    test_close_stuck_bounded(session)
    test_pong_stuck_returns_false(pump)
    test_loop_closes_on_stuck_send(pump)
