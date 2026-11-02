"""Pompe WebSocket ``/live/ws`` : réception, inférence bornée, réponse, anti-zombie.

Extrait de ``service.py`` (garde < 500 lignes/fichier) : la boucle lit une frame,
l'inférence passe par le service (``_run_live*``), la réponse repart avec l'``id``.
Fonctions prenant le service en premier paramètre — ``Sam3LiveService`` ne garde
que de fins délégués (doublures et bancs inchangés).

Double-buffer (FIX-13) : pendant l'inférence de N, la boucle pré-lit le message
suivant (décodage + pré-traitement N+1 recouverts) — UN worker d'inférence, ordre
préservé, +1 frame en mémoire. Ping applicatif (FIX-17) et cancel (FIX-8) :
requêtes texte sans GPU (``{"type":"ping"}``, ``{"cancel":true}``).
"""

from __future__ import annotations

import asyncio
import json

# Cohérence keep-alive (O2) : le client ping à 30 s (WsTransport pingInterval),
# le serveur tue à 45 s — marge 15 s pour un aller-retour lent. Les pings sont
# protocole (pong auto, jamais livrés à la boucle) : une connexion sans frame
# reste un zombie, fermé ici en 1011 ; le client rouvre paresseusement à la
# frame suivante (WsLiveClient.openLocked). 30 < 45 : ne jamais inverser.
WS_IDLE_SECONDS = 45.0  # sans message : pair considéré mort, fermeture 1011 (zombie ≤ 45 s)
# Borne inférence (F4) : le client abandonne à 10 s (steady) / 60 s (froid) mais
# l'inférence abandonnée gardait le verrou GPU et bloquait les frames suivantes.
# 75 s = juste au-dessus du COLD 60 s + marge (aucune frame légitime tuée) ; une
# frame qui dépasse rend 503, la connexion est gardée (recovery 180 → 75 s).
WS_FRAME_SECONDS = 75.0  # inférence max par frame : au-delà, erreur 503, connexion gardée
WS_MAX_REPLY_BYTES = 4_000_000  # réponse plus grosse : fermeture 1009 avant le proxy
# Vol borné à 2 (FIX-13, structurel : 1 inférence + 1 message tenu, pas de file).
WS_MAX_IN_FLIGHT = 2


def classify(raw) -> tuple:
    """Message → ``("ping"|"cancel"|"frame", id)`` ; binaire/illisible = frame.

    Pur (sans GPU, sans I/O) : le binaire et le JSON invalide restent des frames —
    ``ws_frame`` leur répondra 400 comme avant, jamais de changement de contrat.
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
    if payload.get("type") == "ping":
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


async def _send_reply(websocket, raw, reply) -> bool:
    """Log + borne 1009 + envoi ; False = la boucle doit s'arrêter."""
    text = json.dumps(reply, separators=(",", ":"))
    err = reply.get("error")
    status = err.get("status", "ok") if isinstance(err, dict) else "ok"
    print(f"ws id={reply.get('id')} status={status} in={len(raw) if raw else 0}B "
          f"out={len(text)}B objs={len(reply.get('objects', []))}", flush=True)
    if len(text) > WS_MAX_REPLY_BYTES:
        print(f"ws id={reply.get('id')}: {len(text)}B > "
              f"{WS_MAX_REPLY_BYTES}B, fermeture 1009", flush=True)
        await websocket.close(code=1009, reason="response too large")
        return False
    await websocket.send_text(text)
    return True


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
    """Prochain message : ``("msg", raw)`` ou ``("stop", None)`` (idle/disconnect gérés).

    Idle → fermeture 1011 ici ; disconnect → bye propre ; sinon le message brut.
    """
    kind, raw = await _recv_idle(websocket, idle_seconds)
    if kind == "idle":
        print(f"ws: inactif depuis {idle_seconds:g} s, fermeture 1011", flush=True)
        await websocket.close(code=1011, reason="idle")
        return ("stop", None)
    if kind == "disconnect":
        print("ws: disconnect client (fin propre côté app)", flush=True)
        return ("stop", None)
    return ("msg", raw)


async def _pong(websocket, raw) -> bool:
    """Pong immédiat d'un ping applicatif ; False = client parti."""
    try:
        await websocket.send_text(json.dumps(ping_reply(raw), separators=(",", ":")))
    except Exception:  # noqa: BLE001 — client parti pendant le pong
        return False
    return True


async def _finish_held(infer, raw, frame_seconds, remaining, held) -> tuple:
    """N+1 tenue : N finit (bornée) → ``(reply, held, False)``."""
    try:
        reply = await asyncio.wait_for(infer, timeout=remaining)
    except asyncio.TimeoutError:
        return (_frame_timeout_reply(raw, frame_seconds), held, False)
    return (reply, held, False)


def _infer_result(infer, current):
    """Réponse d'une inférence terminée (500 défensif si elle a levé)."""
    from live_ws import error_message

    try:
        return infer.result()
    except asyncio.CancelledError:
        raise
    except Exception as exc:  # noqa: BLE001 — ws_frame ne lève jamais, garde
        return error_message(current, 500, f"internal error: {exc}")


async def _on_prefetched(websocket, infer, raw, kind, raw2, current, frame_seconds,
                         remaining):
    """Message arrivé pendant N : ``("again", waiter)`` ou ``("done", reply, held, stop)``.

    Ping → pong immédiat, N continue ; cancel de N (avec ou sans ``id``) → N
    annulée, 0 réponse ; cancel d'une autre frame → ignoré ; frame → tenue, N
    finit d'abord ; disconnect → N finit quand même (réponse envoyée si
    possible), sortie après l'envoi.
    """
    if kind == "disconnect":
        reply, held, _stop = await _finish_held(infer, raw, frame_seconds, remaining,
                                               None)
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
    reply, held, _stop = await _finish_held(infer, raw, frame_seconds, remaining,
                                           ("msg", raw2))
    return ("done", reply, held, False)


async def _infer_with_prefetch(service, websocket, raw, depth_state, every,
                               frame_seconds) -> tuple:
    """Inférence N + pré-lecture N+1 : ``(reply, held, stop)`` (FIX-13 + FIX-8).

    ``reply`` (None si annulée ou client parti), ``held`` (message N+1 déjà reçu,
    None si aucun — borne ``WS_MAX_IN_FLIGHT``), ``stop`` (client parti : sortir).
    Ping reçu pendant N : pong immédiat, N continue. Cancel de N (avec ou sans
    ``id``) : N annulée (tâche ``ws_frame`` tuée, GPU libéré pour la suivante),
    0 réponse.
    Client parti pendant N : N finit quand même (réponse envoyée si le socket
    l'accepte encore — comme en séquentiel), sortie après l'envoi.
    """
    from live_ws import stuck_id

    current = stuck_id(raw)
    infer = asyncio.create_task(ws_frame(service, raw, depth_state, every))
    waiter = asyncio.create_task(_recv_one(websocket))
    deadline = asyncio.get_running_loop().time() + frame_seconds
    try:
        while True:
            remaining = max(0.0, deadline - asyncio.get_running_loop().time())
            done, _ = await asyncio.wait({infer, waiter}, timeout=remaining,
                                           return_when=asyncio.FIRST_COMPLETED)
            if not done:
                return (_frame_timeout_reply(raw, frame_seconds), None, False)
            if infer in done:
                reply = _infer_result(infer, current)
                if waiter.done():
                    kind_w, raw_w = waiter.result()
                    if kind_w == "disconnect":
                        return (reply, None, True)
                    return (reply, ("msg", raw_w), False)
                waiter.cancel()
                return (reply, None, False)
            kind, raw2 = waiter.result()
            outcome = await _on_prefetched(websocket, infer, raw, kind, raw2, current,
                                           frame_seconds, remaining)
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
                reply = await asyncio.wait_for(ws_frame(service, raw, depth_state, every),
                                               timeout=frame_seconds)
            except asyncio.TimeoutError:
                reply = _frame_timeout_reply(raw, frame_seconds)
        return await _send_reply(websocket, raw, reply)
    except WebSocketDisconnect:
        print("ws: client parti avant la réponse (disconnect)", flush=True)
        return False
    except Exception:  # noqa: BLE001 — client parti entre réception et réponse
        print("ws: client parti avant la réponse (envoi KO)", flush=True)
        return False


async def _serve_frame(service, websocket, raw, depth_state, every,
                     frame_seconds) -> tuple:
    """Une frame via le double-buffer : ``(held, stop)``, réponse déjà envoyée.

    ``held`` = message N+1 tenu (ou None), ``stop`` = sortir (client parti ou 1009).
    Cancel : 0 réponse, ``(None, False)`` — la suivante est déjà attendue.
    """
    reply, held, stop = await _infer_with_prefetch(service, websocket, raw, depth_state,
                                                   every, frame_seconds)
    if reply is not None:
        try:
            sent = await _send_reply(websocket, raw, reply)
        except Exception:  # noqa: BLE001 — client parti entre réception et réponse
            print("ws: client parti avant la réponse (envoi KO)", flush=True)
            return (None, True)
        if stop:
            print("ws: disconnect client en réception", flush=True)
            return (None, True)
        return (held, not sent)
    if stop:
        print("ws: disconnect client en réception", flush=True)
        return (None, True)
    return (None, False)  # cancel : 0 réponse, la suivante est déjà attendue


async def ws_loop(service, websocket, idle_seconds, frame_seconds, every=None) -> None:
    """Double-buffer par connexion ; le verrou GPU global reste la seule file.

    Pendant l'inférence de N, le message suivant est pré-lu (FIX-13) : UN worker
    (ordre préservé), vol borné à ``WS_MAX_IN_FLIGHT`` (2), +1 frame en mémoire.
    Anti-zombie : réception bornée (45 s d'inactivité → fermeture 1011),
    inférence bornée (frame_seconds → erreur 503 à la frame, connexion gardée),
    réponse bornée (4 Mo → fermeture 1009) et fermeture explicite partout.
    L'état du throttle depth est créé ici : un par connexion, jamais global.
    """
    from depth import new_depth_throttle

    depth_state = new_depth_throttle()
    held = None
    try:
        while True:
            if held is None:
                kind, raw = await _next_message(websocket, idle_seconds)
                if kind == "stop":
                    return
            else:
                raw = held[1]
                held = None
            cls, _cid = classify(raw)
            if cls == "ping":
                if not await _pong(websocket, raw):
                    print("ws: client parti avant le pong", flush=True)
                    return
                continue
            if cls == "cancel":
                continue  # rien en vol : déjà répondue ou inconnue, 0 réponse
            held, stop = await _serve_frame(service, websocket, raw, depth_state, every,
                                            frame_seconds)
            if stop:
                return
    finally:
        try:
            await websocket.close()
        except Exception:  # noqa: BLE001 — déjà fermé, rien à faire
            pass
