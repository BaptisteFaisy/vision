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

import json
import time

# Motif « réponse trop grosse » servi dans la ``reason`` : OkHttp la remonte telle
# quelle, le client sait quoi baisser (``max_objects``) ou passer (``mask_format``).
OVERSIZE_REASON = "response too large: lower max_objects or use mask_format=rle"


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


async def send_reply(service, websocket, raw, reply, limit_bytes) -> str | None:
    """Log + dégradation/borne 1009 + envoi ; motif d'arrêt (``None`` = on continue).

    ``"oversize"`` seulement quand la réponse ne peut pas être dégradée : une réponse
    trop grosse devient une réponse plus petite, la session survit.
    """
    text = dumps(reply)
    err = reply.get("error")
    status = err.get("status", "ok") if isinstance(err, dict) else "ok"
    print(f"ws id={reply.get('id')} status={status} in={len(raw) if raw else 0}B "
          f"out={len(text)}B objs={len(reply.get('objects', []))}", flush=True)
    if len(text) <= limit_bytes:
        await websocket.send_text(text)
        return None
    light = degrade_reply(reply, limit_bytes)
    if light is None:
        print(f"ws id={reply.get('id')}: {len(text)}B > {limit_bytes}B, "
              "aucune dégradation possible, fermeture 1009", flush=True)
        ws_metrics(service)["close_oversize"] += 1
        await websocket.close(code=1009, reason=OVERSIZE_REASON)
        return "oversize"
    stats = ws_metrics(service)
    stats["degraded"] += 1
    light_text = dumps(light)
    print(f"ws id={reply.get('id')}: {len(text)}B > {limit_bytes}B, réponse "
          f"dégradée ({len(light_text)}B, masques retirés)", flush=True)
    await websocket.send_text(light_text)
    return None
