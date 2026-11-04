"""Garde-fous du créneau GPU : attente bornée, créneau toujours rendu, chien de garde.

L'inférence tourne dans un **thread** (``asyncio.to_thread``) : annuler l'``await``
qui l'attend — client parti, ``cancel``, timeout de frame — n'arrête pas le thread,
qui continue de tenir ``_gpu_lock``. Trois garde-fous, tous exerçables sans GPU :

* **attente bornée** (:func:`acquire`) : un appelant qui n'obtient pas le verrou dans
  ``GPU_QUEUE_SECONDS`` lève ``GpuBusy`` → ``429`` (signal que le client comprend
  déjà) au lieu de garder un slot ``max_inputs`` pendant tout le budget de frame ;
* **créneau rendu dans tous les chemins** (:class:`Slots`) : le compteur des
  coroutines décroît en ``finally``, donc à l'annulation aussi ; les threads
  réellement vivants sont comptés à part — ``threads > active`` = zombie visible
  dans ``/health`` ;
* **chien de garde** (:func:`note_timeout`) : ``WEDGE_RESTART_TIMEOUTS`` frames
  muettes consécutives = verrou tenu par un thread fou ; seul un redémarrage le
  rend (un thread Python ne se tue pas).
"""

from __future__ import annotations

import asyncio
import os
import threading

# Attente au verrou avant de répondre « occupé » (429) : 15 s couvre la frame
# chaude (~0,25 s) et une frame froide, sans immobiliser un slot plus longtemps.
GPU_QUEUE_SECONDS = float(os.environ.get("FAST_GPU_QUEUE_S") or 15.0)
# POST : borne inférence (WS = 75 s + marge) ; un thread qui ne revient jamais
# rend 503 au lieu de parquer l'input jusqu'au timeout Modal (1 h).
POST_FRAME_SECONDS = 90.0
# POST : lecture du corps (frame ~200 Ko) ; un client qui dégouline rend 400.
POST_BODY_SECONDS = 30.0
# 3 × 75 s de blocage prouvé : au-delà, le thread fou ne rendra jamais le verrou.
WEDGE_RESTART_TIMEOUTS = 3
# Laisse la réponse 429 partir avant que le processus ne meure.
WEDGE_RESTART_DELAY_SECONDS = 0.5


class GpuBusy(RuntimeError):
    """Créneau GPU non obtenu dans le budget : « occupé », pas « lent » (→ 429)."""


def acquire(gpu_lock, timeout: float | None = None) -> bool:
    """``gpu_lock.acquire`` **borné** ; ``None`` (pas de verrou) = rien à attendre."""
    if gpu_lock is None:
        return True
    return gpu_lock.acquire(timeout=GPU_QUEUE_SECONDS if timeout is None else timeout)


def tracked(pipeline, slots, args: tuple, kwargs: dict):
    """Appelle ``pipeline`` en comptant le thread, décrémenté à sa fin **réelle**.

    Appelé *dans* le thread (``asyncio.to_thread``), donc le ``finally`` tourne
    même si la coroutine qui attend a été annulée : c'est la seule mesure honnête
    d'un calcul zombie.
    """
    if slots is None:
        return pipeline(*args, **kwargs)
    slots.thread_enter()
    try:
        return pipeline(*args, **kwargs)
    finally:
        slots.thread_done()


class Slots:
    """Créneaux d'inférence d'un conteneur : un par frame en vol.

    ``active`` décroît en ``finally`` (donc à l'annulation aussi) ; ``threads`` ne
    décroît qu'à la fin réelle du thread ; ``abandoned`` compte les frames dont
    l'``await`` est mort avant la réponse (client parti, cancel, timeout).
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.active = 0
        self.threads = 0
        self.abandoned = 0

    def enter(self) -> None:
        with self._lock:
            self.active += 1

    def leave(self) -> None:
        with self._lock:
            self.active = max(0, self.active - 1)

    def thread_enter(self) -> None:
        with self._lock:
            self.threads += 1

    def thread_done(self) -> None:
        with self._lock:
            self.threads = max(0, self.threads - 1)

    def abandon(self) -> None:
        with self._lock:
            self.abandoned += 1

    def snapshot(self) -> dict:
        with self._lock:
            return {"active": self.active, "threads": self.threads,
                    "abandoned": self.abandoned}


def note_timeout(service, frame_seconds: float) -> int:
    """Compteur de frames muettes **consécutives** ; au seuil, redémarrage planifié.

    Le compteur vit sur le service (un par conteneur) : trois frames muettes, même
    de clients différents, désignent la même carte bloquée. ``os._exit`` coûte un
    boot payé une fois, au lieu d'un direct gelé jusqu'à l'extinction du bail.
    """
    count = getattr(service, "frame_timeouts", 0) + 1
    service.frame_timeouts = count
    print(f"ws: frame muette > {frame_seconds:g} s (timeout {count}/"
          f"{WEDGE_RESTART_TIMEOUTS})", flush=True)
    if count >= WEDGE_RESTART_TIMEOUTS:
        print("ws: carte bloquée (verrou tenu par un thread) — redémarrage du conteneur",
              flush=True)
        restart_later()
    return count


def note_success(service) -> None:
    """Une frame a répondu : le compteur de timeouts consécutifs repart à zéro."""
    service.frame_timeouts = 0


async def post_guarded(service, coro, frame_seconds: float | None = None):
    """POST borné + watchdog : 503 + compteur, jamais un input parqué (M1).

    Le watchdog était aveugle hors WS : un wedge déclenché par POST ne
    redémarrait jamais seul. Ici chaque timeout compte (→ ``os._exit(3)`` au
    seuil) et chaque succès remet le compteur à zéro, comme en WS.
    """
    from fastapi import HTTPException

    bound = POST_FRAME_SECONDS if frame_seconds is None else frame_seconds
    try:
        result = await asyncio.wait_for(coro, timeout=bound)
    except asyncio.TimeoutError:
        slots = getattr(service, "slots", None)
        if slots is not None:
            slots.abandon()
        note_timeout(service, bound)
        raise HTTPException(status_code=503, detail="inference timeout, retry") from None
    note_success(service)
    return result


def restart_later(delay: float = WEDGE_RESTART_DELAY_SECONDS) -> None:
    """``os._exit(3)`` après la réponse (jamais de gel : un redémarrage rend le verrou)."""
    threading.Timer(delay, os._exit, args=(3,)).start()


def wedged(service) -> bool:
    """Le seuil est-il atteint ? Publier ``ok:false`` avant que le processus ne meure."""
    return getattr(service, "frame_timeouts", 0) >= WEDGE_RESTART_TIMEOUTS
