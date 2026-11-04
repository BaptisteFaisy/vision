"""Session WebSocket ``/live/ws`` : compteurs publiés par ``/health`` et envoi des réponses.

Extrait de ``ws_pump`` (garde < 500 lignes/fichier). Deux responsabilités, toutes deux
exercables sans GPU :

* **compteurs** — une ligne par ouverture et par fermeture, et ``/health`` publie la
  photo : sans eux le taux de reconnexion n'est pas mesurable (P1-3) ;
* **envoi** — une réponse trop grosse est **dégradée** (moitié des objets, masques
  retirés) avant de fermer : un ``1009`` immédiat renvoyait le client vers la même
  requête, donc vers le même ``1009`` (P2-1).

Le plafond d'octets est passé en argument (jamais lu ici) : ``ws_pump`` reste la seule
autorité du contrat, bancs et doublures peuvent le surcharger.
"""

from __future__ import annotations

import asyncio
import json
import time

# Motif « réponse trop grosse » servi dans la ``reason`` : OkHttp la remonte telle
# quelle, le client sait quoi baisser (``max_objects``) ou passer (``mask_format``).
OVERSIZE_REASON = "response too large: lower max_objects or use mask_format=rle"

# Borne d'envoi (P0-2) : un ``send_text`` sans borne sur un pair parti ne revient
# jamais — la session reste « open », l'idle (qui ne surveille que ``receive()``)
# ne la ferme pas, et le créneau d'entrée Modal (``max_inputs``) reste occupé à vie.
# À 4 sockets parqués, ``/health`` et ``/live`` font la queue au proxy sans fin.
# 5 s : un pair vivant absorbe une réponse en millisecondes ; au-delà il est parti.
WS_SEND_SECONDS = 5.0
# Handshake : au-delà, le slot est rendu sans session (M1 : accept() nu parquait
# l'input en silence, avant tout log et tout compteur).
WS_ACCEPT_SECONDS = 10.0


async def accept_ws(websocket) -> bool:
    """Handshake gardé : ``True`` + session ouverte, ``False`` = slot rendu, 0 session.

    Un handshake est un input ``max_inputs`` comme un autre : sans la borne, un
    client qui dégouline parque le slot jusqu'au timeout Modal (1 h). La durée
    est loggée (sonde F3) ; un pair parti avant ``accept()`` rend 0 session.
    """
    started = time.monotonic()
    try:
        await asyncio.wait_for(websocket.accept(), timeout=WS_ACCEPT_SECONDS)
    except asyncio.TimeoutError:
        print(f"ws: handshake > {WS_ACCEPT_SECONDS:g} s, slot rendu sans session",
              flush=True)
        return False
    except Exception as exc:  # noqa: BLE001 — pair parti pendant le handshake
        print(f"ws: accept impossible ({exc}), 0 session", flush=True)
        return False
    took_ms = (time.monotonic() - started) * 1000
    print(f"ws: accept /live/ws en {took_ms:.0f} ms", flush=True)
    return True


def dumps(reply) -> str:
    """Sérialisation compacte : une seule règle pour tous les envois du contrat."""
    return json.dumps(reply, separators=(",", ":"))


def new_ws_metrics() -> dict:
    """Compteurs du direct (un dict par conteneur, publié tel quel par ``/health``)."""
    return {"sessions": 0, "open": 0, "frames": 0, "errors": 0, "timeouts": 0,
            "degraded": 0, "close_idle": 0, "close_oversize": 0, "close_client": 0,
            "last_frame_at": None}


def ws_metrics(service) -> dict:
    """Compteurs du service, créés paresseusement (doublures de test incluses)."""
    stats = getattr(service, "ws", None)
    if not isinstance(stats, dict):
        stats = new_ws_metrics()
        service.ws = stats
    return stats


def ws_snapshot(service) -> dict:
    """Photo pour ``/health`` : compteurs, créneaux et âge de la dernière frame.

    ``threads > active`` = frame abandonnée encore en calcul (invisible autrement) ;
    ``last_frame_age_s`` permet au client de dater la dernière inférence servie.
    """
    stats = dict(ws_metrics(service))
    slots = getattr(service, "slots", None)
    if slots is not None:
        stats.update(slots.snapshot())
    last = stats.pop("last_frame_at", None)
    stats["last_frame_age_s"] = None if last is None else round(time.monotonic() - last, 1)
    return stats


def degrade_reply(reply: dict, limit_bytes: int) -> dict | None:
    """Réponse allégée sous le plafond : moitié des objets, masques retirés.

    Une réponse > 4 Mo ne doit pas tuer la session : le client renverrait la même
    requête et retomberait sur le même ``1009``. On rend donc une réponse servable,
    marquée ``degraded`` (champ additif, ignoré par un client ancien), et on ne ferme
    que si même les boîtes dépassent le plafond (``None``).
    """
    objects = reply.get("objects")
    if not isinstance(objects, list) or not objects:
        return None
    if not all(isinstance(obj, dict) for obj in objects):
        return None
    kept = max(1, len(objects) // 2)
    light = dict(reply)
    light["objects"] = [{key: value for key, value in obj.items() if key != "mask_b64"}
                        for obj in objects[:kept]]
    light["mask_encoding"] = "none"
    light["degraded"] = {"reason": "reply too large", "max_objects": kept,
                         "mask_encoding": "none"}
    return light if len(dumps(light)) <= limit_bytes else None


async def _bounded(coro, seconds: float) -> bool:
    """Une opération socket bornée : ``False`` = pair parti (timeout ou erreur)."""
    try:
        await asyncio.wait_for(coro, timeout=seconds)
        return True
    except asyncio.TimeoutError:
        return False
    except Exception:  # noqa: BLE001 — socket déjà fermé : pair parti
        return False


async def send_text_bounded(websocket, text: str,
                            seconds: float | None = None) -> bool:
    """Envoi borné : ``False`` = le pair est parti, la session doit se fermer."""
    bound = WS_SEND_SECONDS if seconds is None else seconds
    return await _bounded(websocket.send_text(text), bound)


async def close_bounded(websocket, code: int = 1000, reason: str | None = None,
                        seconds: float | None = None) -> bool:
    """Fermeture bornée : un ``close()`` peut parquer comme un envoi."""
    bound = WS_SEND_SECONDS if seconds is None else seconds
    return await _bounded(websocket.close(code=code, reason=reason), bound)


async def send_reply(service, websocket, raw, reply, limit_bytes,
                     send_seconds: float | None = None) -> str | None:
    """Log + dégradation/borne 1009 + envoi ; motif d'arrêt (``None`` = on continue).

    ``"oversize"`` seulement quand la réponse ne peut pas être dégradée : une réponse
    trop grosse devient une réponse plus petite, la session survit. ``"client"``
    quand l'envoi borné échoue : le pair est parti, la boucle sort et le créneau
    d'entrée est rendu au lieu de parquer la session (P0-2).
    """
    bound = WS_SEND_SECONDS if send_seconds is None else send_seconds
    text = dumps(reply)
    err = reply.get("error")
    status = err.get("status", "ok") if isinstance(err, dict) else "ok"
    print(f"ws id={reply.get('id')} status={status} in={len(raw) if raw else 0}B "
          f"out={len(text)}B objs={len(reply.get('objects', []))}", flush=True)
    if len(text) <= limit_bytes:
        if await send_text_bounded(websocket, text, bound):
            return None
        print(f"ws id={reply.get('id')}: envoi impossible (pair parti), "
              "session fermée", flush=True)
        return "client"
    light = degrade_reply(reply, limit_bytes)
    if light is None:
        print(f"ws id={reply.get('id')}: {len(text)}B > {limit_bytes}B, "
              "aucune dégradation possible, fermeture 1009", flush=True)
        ws_metrics(service)["close_oversize"] += 1
        await close_bounded(websocket, code=1009, reason=OVERSIZE_REASON)
        return "oversize"
    stats = ws_metrics(service)
    stats["degraded"] += 1
    light_text = dumps(light)
    print(f"ws id={reply.get('id')}: {len(text)}B > {limit_bytes}B, réponse "
          f"dégradée ({len(light_text)}B, masques retirés)", flush=True)
    if await send_text_bounded(websocket, light_text, bound):
        return None
    print(f"ws id={reply.get('id')}: envoi dégradé impossible (pair parti), "
          "session fermée", flush=True)
    return "client"
