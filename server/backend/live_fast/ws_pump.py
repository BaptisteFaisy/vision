"""Pompe WebSocket ``/live/ws`` : réception, inférence bornée, réponse, anti-zombie.

Extrait de ``service.py`` (garde < 500 lignes/fichier) : la boucle lit une frame,
l'inférence passe par le service (``_run_live*``), la réponse repart avec l'``id``.
Fonctions prenant le service en premier paramètre — ``Sam3LiveService`` ne garde
que de fins délégués (doublures et bancs inchangés).
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


async def ws_frame(service, raw, depth_state=None, every=None) -> dict:
    """Un message texte ou binaire → un message texte (résultat ou erreur) ; ne lève jamais.

    Une frame invalide (JSON cassé, ``id`` absent, base64/JPEG illisible, GPU en
    vrac) ne ferme pas la connexion : elle repart en message d'erreur, avec l'``id``
    connu (``-1`` si l'en-tête est illisible). ``depth_state`` (un par connexion,
    créé par ``ws_loop``) porte le throttle depth (voir ``depth.py``). ``every``
    (throttle depth) vient du service — None = défaut du déployé.
    """
    from fastapi import HTTPException

    from depth import depth_throttle_in, depth_throttle_out
    from live_ws import error_message, parse_frame, parse_frame_bytes, success_message

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

    Renvoie False quand la boucle doit s'arrêter, True pour continuer.
    """
    from fastapi import WebSocketDisconnect

    from live_ws import UNKNOWN_ID, error_message, stuck_id

    try:
        if raw is None:
            reply = error_message(UNKNOWN_ID, 400, "expected a text or binary message")
        else:
            try:
                reply = await asyncio.wait_for(ws_frame(service, raw, depth_state, every),
                                               timeout=frame_seconds)
            except asyncio.TimeoutError:
                stuck = stuck_id(raw)
                print(f"ws: frame id={stuck} > {frame_seconds:g} s, erreur 503",
                      flush=True)
                reply = error_message(stuck, 503, "inference timeout, retry")
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
    except WebSocketDisconnect:
        print("ws: client parti avant la réponse (disconnect)", flush=True)
        return False
    except Exception:  # noqa: BLE001 — client parti entre réception et réponse
        print("ws: client parti avant la réponse (envoi KO)", flush=True)
        return False


async def ws_loop(service, websocket, idle_seconds, frame_seconds, every=None) -> None:
    """Une frame à la fois par connexion ; le verrou GPU global reste la seule file.

    Anti-zombie : réception bornée (45 s d'inactivité → fermeture 1011),
    inférence bornée (frame_seconds → erreur 503 à la frame, connexion gardée),
    réponse bornée (4 Mo → fermeture 1009) et fermeture explicite partout.
    Sans ces bornes, chaque timeout client laisse une connexion pendante 300 s,
    et deux zombies saturent le conteneur (toute reconnexion queue alors
    indéfiniment : c'est le freeze observé). Seuils en paramètres pour les tests.
    L'état du throttle depth est créé ici : un par connexion, jamais global.
    """
    from fastapi import WebSocketDisconnect

    from depth import new_depth_throttle

    depth_state = new_depth_throttle()
    try:
        while True:
            try:
                message = await asyncio.wait_for(websocket.receive(),
                                                 timeout=idle_seconds)
                if message["type"] == "websocket.disconnect":
                    print("ws: disconnect client (fin propre côté app)", flush=True)
                    return
                raw = message.get("text", message.get("bytes"))
            except asyncio.TimeoutError:
                print(f"ws: inactif depuis {idle_seconds:g} s, fermeture 1011", flush=True)
                await websocket.close(code=1011, reason="idle")
                return
            except WebSocketDisconnect:
                print("ws: disconnect client en réception", flush=True)
                return
            except Exception:  # noqa: BLE001 — socket déjà fermé
                raw = None
            if not await ws_answer(service, websocket, raw, frame_seconds, depth_state,
                                   every):
                return
    finally:
        try:
            await websocket.close()
        except Exception:  # noqa: BLE001 — déjà fermé, rien à faire
            pass
