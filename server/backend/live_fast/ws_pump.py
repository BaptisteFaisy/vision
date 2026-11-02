"""Pompe WebSocket ``/live/ws`` : réception, inférence bornée, réponse, anti-zombie.

Extrait de ``service.py`` (garde < 500 lignes/fichier) : la boucle lit une frame,
l'inférence passe par le service (``_run_live*``), la réponse repart avec l'``id``.
Fonctions prenant le service en premier paramètre — ``Sam3LiveService`` ne garde
que de fins délégués (doublures et bancs inchangés). Les compteurs et l'envoi
(dégradation incluse) vivent dans ``ws_session`` ; les garde-fous GPU dans
``gpu_guard``.

Double-buffer (FIX-13) : pendant l'inférence de N, la boucle pré-lit le message
suivant (décodage + pré-traitement N+1 recouverts) — UN worker d'inférence, ordre
préservé, +1 frame en mémoire. Ping applicatif (FIX-17) et cancel (FIX-8) :
requêtes texte sans GPU (``{"type":"ping"}``, ``{"cancel":true}``).

Reprise après coupure (contrat ``docs/LIVE-SPEC.md`` §9) : ``1001`` « idle »
(plus ``1011``, qui veut dire « serveur cassé »), ``1009`` seulement si la réponse
reste trop grosse même dégradée, ``429`` pour « occupé », et le créneau d'entrée
est rendu dans **tous** les chemins (client parti, cancel, timeout) — voir
``_infer_guarded`` et ``gpu_guard``.
"""

from __future__ import annotations

import asyncio
import json
import time

from gpu_guard import note_success, note_timeout
from ws_session import dumps, send_reply, ws_metrics

# Réveil/keepalive (P1-1) : le client ping le **protocole** toutes les 30 s
# (``WsTransport`` ``pingInterval``) ; ces pings ne sont JAMAIS livrés à
# ``websocket.receive()``, ils ne réarment donc rien ici. La marge réelle d'un
# socket sain est ``WS_IDLE_SECONDS`` moins l'intervalle inter-frames (0,35-0,9 s),
# pas « idle - ping ». D'où 120 s = 4 pings client : une pause légitime (dictée,
# réglages, caméra re-liée) ne ferme plus une session vivante, et un zombie passif
# coûte au plus 2 min. Le keepalive applicatif (``{"id":0,"ping":1}``) réarme le
# chrono, lui. Ne jamais descendre sous ``WS_CLIENT_PING_SECONDS``.
WS_CLIENT_PING_SECONDS = 30.0  # ping protocole du client (contrat LIVE-SPEC §1)
WS_IDLE_SECONDS = 120.0  # sans message applicatif : fermeture 1001 « idle »
# Borne inférence (F4) : le client abandonne à 10 s (steady) / 60 s (froid) mais
# l'inférence abandonnée gardait le verrou GPU et bloquait les frames suivantes.
# 75 s = juste au-dessus du COLD 60 s + marge (aucune frame légitime tuée) ; une
# frame qui dépasse rend 503, la connexion est gardée.
WS_FRAME_SECONDS = 75.0  # inférence max par frame : au-delà, erreur 503, connexion gardée
WS_MAX_REPLY_BYTES = 4_000_000  # réponse plus grosse : dégradée, 1009 en dernier recours
# Client parti pendant N : on laisse finir la réponse au plus ça, puis on rend le
# créneau. Le thread d'inférence, lui, va au bout (un thread Python ne se tue pas).
WS_ABANDON_SECONDS = 2.0
# Vol borné à 2 (FIX-13, structurel : 1 inférence + 1 message tenu, pas de file).
WS_MAX_IN_FLIGHT = 2


def classify(raw) -> tuple:
    """Message → ``("ping"|"cancel"|"frame", id)`` ; binaire/illisible = frame.

    Keepalive accepté sous deux formes : ``{"type":"ping","id":N}`` (FIX-17) et
    ``{"id":0,"ping":1}`` (forme courte, sans ``type``). Toutes deux sans GPU et
    réarmant le chrono d'inactivité ; pur (sans I/O), le binaire et le JSON
    invalide restent des frames — ``ws_frame`` leur répondra 400 comme avant.
    """
    # Paresseux : `modal deploy` importe ce module dans le runner CI, sans
    # pydantic (service.py faisait déjà ainsi avant l'extraction).
    from live_ws import frame_id

    if not isinstance(raw, str):
        return ("frame", -1)
    try:
        payload = json.loads(raw)
    except (TypeError, ValueError):
        return ("frame", -1)
    if not isinstance(payload, dict):
        return ("frame", -1)
    if payload.get("type") == "ping" or payload.get("ping") == 1:
        return ("ping", frame_id(payload.get("id")))
    if payload.get("cancel") is True:
        return ("cancel", frame_id(payload.get("id")))
    return ("frame", frame_id(payload.get("id")))


def ping_reply(raw) -> dict | None:
    """``{"id": N, "pong": true}`` si ``raw`` est un ping, sinon None (sans GPU)."""
    kind, ident = classify(raw)
    if kind != "ping":
        return None
    return {"id": ident, "pong": True}


def _frame_timeout_reply(raw, frame_seconds) -> dict:
    """Erreur 503 d'une frame lente, avec l'``id`` connu (``-1`` si illisible)."""
    from live_ws import error_message, stuck_id

    stuck = stuck_id(raw)
    print(f"ws: frame id={stuck} > {frame_seconds:g} s, erreur 503", flush=True)
    return error_message(stuck, 503, "inference timeout, retry")


def _note_timeout(service, frame_seconds) -> None:
    """Un timeout de frame : compteur publié, chien de garde au seuil (``gpu_guard``)."""
    ws_metrics(service)["timeouts"] += 1
    note_timeout(service, frame_seconds)


async def _recv_one(websocket) -> tuple:
    """Une réception : ``("msg", raw)`` ou ``("disconnect", None)`` ; ne lève jamais."""
    from fastapi import WebSocketDisconnect

    try:
        message = await websocket.receive()
    except WebSocketDisconnect:
        return ("disconnect", None)
    except RuntimeError:
        # Starlette (``WebSocketDisconnected``) : « receive() après disconnect
        # consommé » — le waiter du double-buffer peut consommer le disconnect
        # avant la boucle : le client est parti, sortie propre (pas un 400).
        return ("disconnect", None)
    except Exception:  # noqa: BLE001 — socket déjà fermé : 400, connexion gardée
        return ("msg", None)
    if message.get("type") == "websocket.disconnect":
        return ("disconnect", None)
    return ("msg", message.get("text", message.get("bytes")))


async def _recv_idle(websocket, idle_seconds) -> tuple:
    """``_recv_one`` bornée : ``("idle", None)`` après ``idle_seconds`` de silence."""
    try:
        return await asyncio.wait_for(_recv_one(websocket), timeout=idle_seconds)
    except asyncio.TimeoutError:
        return ("idle", None)


async def _next_message(websocket, idle_seconds) -> tuple:
    """Prochain message : ``("msg", raw)`` ou ``("stop", motif)``.

    Idle → fermeture **1001** « idle » : ``1011`` signifie « erreur interne », un
    client qui sait lire les codes en déduisait « serveur cassé » et backoffait
    pour rien. Disconnect → bye propre, sans fermeture (le pair est déjà parti).
    """
    kind, raw = await _recv_idle(websocket, idle_seconds)
    if kind == "idle":
        print(f"ws: inactif depuis {idle_seconds:g} s, fermeture 1001 idle", flush=True)
        await websocket.close(code=1001, reason="idle")
        return ("stop", "idle")
    if kind == "disconnect":
        print("ws: disconnect client (fin propre côté app)", flush=True)
        return ("stop", "client")
    return ("msg", raw)


async def _pong(websocket, raw) -> bool:
    """Pong immédiat d'un ping applicatif ; False = client parti."""
    try:
        await websocket.send_text(dumps(ping_reply(raw)))
    except Exception:  # noqa: BLE001 — client parti pendant le pong
        return False
    return True


async def _infer_guarded(service, raw, depth_state, every) -> dict:
    """Une inférence avec son créneau : rendu en ``finally``, dans **tous** les chemins.

    Client parti, ``cancel``, timeout de frame : la coroutine meurt, le ``finally``
    passe — le créneau (``max_inputs``) est rendu au lieu de rester occupé jusqu'au
    bout du budget. Le thread, lui, finit son calcul (P0-1) et reste compté par
    ``gpu_guard`` ; ses timeouts consécutifs déclenchent le chien de garde.
    """
    slots = getattr(service, "slots", None)
    if slots is not None:
        slots.enter()
    done = False
    try:
        reply = await ws_frame(service, raw, depth_state, every)
        done = True
    finally:
        if slots is not None:
            slots.leave()
            if not done:
                slots.abandon()
    stats = ws_metrics(service)
    stats["frames"] += 1
    if isinstance(reply.get("error"), dict):
        stats["errors"] += 1
    else:
        stats["last_frame_at"] = time.monotonic()
        note_success(service)
    return reply


async def _finish_held(service, infer, raw, frame_seconds, remaining, held) -> tuple:
    """N+1 tenue : N finit (bornée) → ``(reply, held, False)``."""
    try:
        return (await asyncio.wait_for(infer, timeout=remaining), held, False)
    except asyncio.TimeoutError:
        _note_timeout(service, frame_seconds)
        return (_frame_timeout_reply(raw, frame_seconds), held, False)


async def _finish_abandoned(infer, budget) -> tuple:
    """Client parti : laisse finir N ``budget`` s au plus, puis rend le créneau.

    Une réponse déjà prête part quand même (le socket peut encore l'accepter) ;
    au-delà on abandonne sans rien envoyer — inutile de payer 75 s de GPU pour
    un pair qui n'écoute plus. ``wait_for`` annule la tâche, donc le ``finally``
    de ``_infer_guarded`` rend le créneau tout de suite.
    """
    try:
        return (await asyncio.wait_for(infer, timeout=budget), None, False)
    except asyncio.TimeoutError:
        return (None, None, False)


def _infer_result(infer, current):
    """Réponse d'une inférence terminée (500 défensif si elle a levé)."""
    from live_ws import error_message

    try:
        return infer.result()
    except asyncio.CancelledError:
        raise
    except Exception as exc:  # noqa: BLE001 — ws_frame ne lève jamais, garde
        return error_message(current, 500, f"internal error: {exc}")


def _settled(infer, waiter, current) -> tuple:
    """N finie : ``(reply, held, stop)`` — la pré-lecture est tenue ou jetée."""
    reply = _infer_result(infer, current)
    if waiter.done():
        kind_w, raw_w = waiter.result()
        if kind_w == "disconnect":
            return (reply, None, True)
        return (reply, ("msg", raw_w), False)
    waiter.cancel()
    return (reply, None, False)


async def _on_prefetched(service, websocket, infer, raw, kind, raw2, current,
                          frame_seconds, remaining):
    """Message arrivé pendant N : ``("again", waiter)`` ou ``("done", reply, held, stop)``.

    Ping → pong immédiat, N continue ; cancel de N (avec ou sans ``id``) → N
    annulée, 0 réponse ; cancel d'une autre frame → ignoré ; frame → tenue, N
    finit d'abord ; disconnect → N finit au plus ``WS_ABANDON_SECONDS``, puis le
    créneau est rendu (plus de zombie de 75 s sur un pair parti).
    """
    if kind == "disconnect":
        reply, held, _stop = await _finish_abandoned(infer, min(remaining,
                                                                WS_ABANDON_SECONDS))
        return ("done", reply, held, True)
    cls, cid = classify(raw2)
    if cls == "ping":
        if not await _pong(websocket, raw2):
            return ("done", None, None, True)
        return ("again", asyncio.create_task(_recv_one(websocket)))
    if cls == "cancel" and (cid == current or cid == -1):
        print(f"ws: frame id={current} annulée (cancel client), 0 réponse", flush=True)
        return ("done", None, None, False)
    if cls == "cancel":
        return ("again", asyncio.create_task(_recv_one(websocket)))
    reply, held, _stop = await _finish_held(service, infer, raw, frame_seconds, remaining,
                                            ("msg", raw2))
    return ("done", reply, held, False)


async def _infer_with_prefetch(service, websocket, raw, depth_state, every,
                               frame_seconds) -> tuple:
    """Inférence N + pré-lecture N+1 : ``(reply, held, stop)`` (FIX-13 + FIX-8).

    ``reply`` (None si annulée ou client parti), ``held`` (message N+1 déjà reçu,
    None si aucun — borne ``WS_MAX_IN_FLIGHT``), ``stop`` (client parti : sortir).
    Ping reçu pendant N : pong immédiat, N continue. Cancel de N (avec ou sans
    ``id``) : N annulée (tâche ``ws_frame`` tuée, créneau rendu), 0 réponse.
    Client parti pendant N : N finit au plus ``WS_ABANDON_SECONDS`` (réponse
    envoyée si possible), puis le créneau est rendu.
    """
    from live_ws import stuck_id

    current = stuck_id(raw)
    infer = asyncio.create_task(_infer_guarded(service, raw, depth_state, every))
    waiter = asyncio.create_task(_recv_one(websocket))
    deadline = asyncio.get_running_loop().time() + frame_seconds
    try:
        while True:
            remaining = max(0.0, deadline - asyncio.get_running_loop().time())
            done, _ = await asyncio.wait({infer, waiter}, timeout=remaining,
                                           return_when=asyncio.FIRST_COMPLETED)
            if not done:
                _note_timeout(service, frame_seconds)
                return (_frame_timeout_reply(raw, frame_seconds), None, False)
            if infer in done:
                return _settled(infer, waiter, current)
            kind, raw2 = waiter.result()
            outcome = await _on_prefetched(service, websocket, infer, raw, kind, raw2,
                                           current, frame_seconds, remaining)
            if outcome[0] == "again":
                waiter = outcome[1]
            else:
                return outcome[1:]
    finally:
        pending = [task for task in (infer, waiter) if not task.done()]
        for task in pending:
            task.cancel()
        if pending:  # ramasse les annulées : pas d'exception orpheline en boucle longue
            await asyncio.gather(*pending, return_exceptions=True)


async def ws_frame(service, raw, depth_state=None, every=None) -> dict:
    """Un message texte ou binaire → un message texte (résultat ou erreur) ; ne lève jamais.

    Une frame invalide (JSON cassé, ``id`` absent, base64/JPEG illisible, GPU en
    vrac) ne ferme pas la connexion : elle repart en message d'erreur, avec l'``id``
    connu (``-1`` si l'en-tête est illisible). ``depth_state`` (un par connexion,
    créé par ``ws_loop``) porte le throttle depth (voir ``depth.py``). ``every``
    (throttle depth) vient du service — None = défaut du déployé.
    Ping applicatif (FIX-17) : ``{"type":"ping","id":N}`` → ``{"id":N,"pong":true}``
    sans GPU ; tout message reçu reset l'idle (la réception a abouti).
    """
    from fastapi import HTTPException

    from depth import depth_throttle_in, depth_throttle_out
    from live_ws import error_message, parse_frame, parse_frame_bytes, success_message

    pong = ping_reply(raw)
    if pong is not None:
        return pong
    if every is None:
        from fast_core import DEPTH_EVERY

        every = DEPTH_EVERY
    binary = isinstance(raw, (bytes, bytearray))
    parsed = parse_frame_bytes(bytes(raw)) if binary else parse_frame(raw)
    if parsed["error"] is not None:
        return error_message(parsed["id"], parsed["error"]["status"],
                             parsed["error"]["detail"])
    request = parsed["request"]
    request, cache, repeat = depth_throttle_in(request, depth_state, every)
    try:
        if binary:
            result = await service._run_live_binary_ws(request, parsed["jpeg"])
        else:
            result = await service._run_live(request)
    except HTTPException as exc:
        return error_message(parsed["id"], exc.status_code, exc.detail)
    except Exception as exc:  # noqa: BLE001 — le contrat interdit la fermeture
        return error_message(parsed["id"], 500, f"internal error: {exc}")
    depth_throttle_out(result.get("objects") or [], cache, repeat, depth_state)
    return success_message(result, parsed["id"])


async def ws_answer(service, websocket, raw, frame_seconds, depth_state=None,
                    every=None) -> bool:
    """Traite un message reçu : inférence et réponse bornées, envoi.

    Voie séquentielle (une frame, une réponse) ; ``ws_loop`` utilise la voie
    double-buffer. Renvoie False quand la boucle doit s'arrêter, True sinon.
    """
    from fastapi import WebSocketDisconnect

    from live_ws import UNKNOWN_ID, error_message

    try:
        if raw is None:
            reply = error_message(UNKNOWN_ID, 400, "expected a text or binary message")
        else:
            try:
                reply = await asyncio.wait_for(
                    _infer_guarded(service, raw, depth_state, every), timeout=frame_seconds)
            except asyncio.TimeoutError:
                _note_timeout(service, frame_seconds)
                reply = _frame_timeout_reply(raw, frame_seconds)
        reason = await send_reply(service, websocket, raw, reply, WS_MAX_REPLY_BYTES)
        return reason is None
    except WebSocketDisconnect:
        print("ws: client parti avant la réponse (disconnect)", flush=True)
        return False
    except Exception:  # noqa: BLE001 — client parti entre réception et réponse
        print("ws: client parti avant la réponse (envoi KO)", flush=True)
        return False


async def _serve_frame(service, websocket, raw, depth_state, every,
                       frame_seconds) -> tuple:
    """Une frame via le double-buffer : ``(held, motif d'arrêt ou None)``.

    ``held`` = message N+1 tenu (ou None) ; motif = ``"client"`` (pair parti) ou
    ``"oversize"`` (réponse indégradable), ``None`` = la session continue.
    Cancel : 0 réponse, ``(None, None)`` — la suivante est déjà attendue.
    """
    reply, held, stop = await _infer_with_prefetch(service, websocket, raw, depth_state,
                                                   every, frame_seconds)
    if reply is not None:
        try:
            reason = await send_reply(service, websocket, raw, reply, WS_MAX_REPLY_BYTES)
        except Exception:  # noqa: BLE001 — client parti entre réception et réponse
            print("ws: client parti avant la réponse (envoi KO)", flush=True)
            return (None, "client")
        if reason is not None:
            return (None, reason)
        if stop:
            print("ws: disconnect client en réception", flush=True)
            return (None, "client")
        return (held, None)
    if stop:
        print("ws: disconnect client en réception", flush=True)
        return (None, "client")
    return (None, None)  # cancel : 0 réponse, la suivante est déjà attendue


async def _one_message(service, websocket, raw, depth_state, every,
                       frame_seconds) -> tuple:
    """Un message déjà reçu : ``(held, motif d'arrêt ou None)``.

    Ping → pong (le pair peut être parti : motif ``"client"``), cancel sans vol →
    rien, frame → servie (``_serve_frame``).
    """
    cls, _cid = classify(raw)
    if cls == "ping":
        if not await _pong(websocket, raw):
            print("ws: client parti avant le pong", flush=True)
            return (None, "client")
        return (None, None)
    if cls == "cancel":
        return (None, None)  # rien en vol : déjà répondue ou inconnue, 0 réponse
    return await _serve_frame(service, websocket, raw, depth_state, every, frame_seconds)


async def _close_session(websocket, stats, reason, started) -> None:
    """Fermeture : compteur du motif, ligne de session, ``close()`` explicite.

    Un seul endroit tient le contrat de fermeture (le motif est aussi celui de la
    dernière ``reason`` envoyée au pair) : la ligne ``ws: close`` est ce qui rend
    le taux de reconnexion mesurable après coup.
    """
    stats["open"] = max(0, stats["open"] - 1)
    stats[f"close_{reason}"] = stats.get(f"close_{reason}", 0) + 1
    print(f"ws: close reason={reason} open={stats['open']} "
          f"dur={time.monotonic() - started:.1f}s frames={stats['frames']}", flush=True)
    try:
        await websocket.close()
    except Exception:  # noqa: BLE001 — déjà fermé, rien à faire
        pass


async def ws_loop(service, websocket, idle_seconds, frame_seconds, every=None) -> None:
    """Double-buffer par connexion ; le verrou GPU global reste la seule file.

    Pendant l'inférence de N, le message suivant est pré-lu (FIX-13) : UN worker
    (ordre préservé), vol borné à ``WS_MAX_IN_FLIGHT`` (2), +1 frame en mémoire.
    Anti-zombie : réception bornée (``WS_IDLE_SECONDS`` sans message applicatif →
    fermeture ``1001`` « idle »), inférence bornée (``frame_seconds`` → erreur 503
    à la frame, connexion gardée), réponse bornée (``WS_MAX_REPLY_BYTES`` →
    dégradation, ``1009`` en dernier recours) et fermeture explicite partout.
    L'état du throttle depth est créé ici : un par connexion, jamais global.
    Une ligne par session à l'ouverture et à la fermeture (``/health`` publie les
    mêmes compteurs) : sans elles, le taux de reconnexion n'est pas mesurable.
    """
    from depth import new_depth_throttle

    depth_state = new_depth_throttle()
    stats = ws_metrics(service)
    stats["sessions"] += 1
    stats["open"] += 1
    started = time.monotonic()
    print(f"ws: open sessions={stats['sessions']} open={stats['open']}", flush=True)
    reason = "client"
    held = None
    try:
        while True:
            if held is None:
                kind, raw = await _next_message(websocket, idle_seconds)
                if kind == "stop":
                    reason = raw
                    return
            else:
                raw = held[1]
                held = None
            held, reason = await _one_message(service, websocket, raw, depth_state, every,
                                              frame_seconds)
            if reason is not None:
                return
    finally:
        await _close_session(websocket, stats, reason, started)
