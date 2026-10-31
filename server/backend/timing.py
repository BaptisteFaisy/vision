"""Chronos par étage du direct ``/live`` — stdlib seule, aucun GPU, aucun import lourd.

Le conteneur de prod mesure déjà ses étages (``fast_core`` remplit ``timers``) mais le
levier ``FAST_TIMING`` est éteint en production : la réponse ne porte que ``elapsed_ms``,
un chiffre seul qui ne dit rien. Ce module ajoute les briques qui manquaient, sans
dupliquer la mesure existante :

* :class:`Stage` — ``with Stage(timers, "queue_ms"):`` chronomètre un bloc dans la même
  carte que les postes déjà remplis par ``fast_core`` ;
* :func:`format_server_timing` — l'en-tête HTTP ``Server-Timing`` (RFC 8673) que le banc
  téléphone peut lire sans parser le corps de la réponse ;
* :func:`format_boot_cpu` — la ligne ``boot_cpu: ...`` du démarrage CPU : grep sans
  faux positif dans les logs Modal, durées à la même décimale que les postes.

Coût mesuré de l'instrumentation : ~0,6 µs pour six marques ``perf_counter`` — négligeable
face aux 82-125 ms du serveur. L'horloge est **monotone** (``perf_counter``) : elle ne
subit ni l'heure système ni les changements de fuseau du conteneur, et ses valeurs ne sont
comparables qu'à l'intérieur du même conteneur (c'est le cas : les postes sont produits et
consommés dans la même frame).
"""

from __future__ import annotations

import os
import time

# Une décimale : la précision utile est le dixième de ms, le bruit de mesure est du
# demi-microseconde. Arrondir ici garde la charge utile compacte (6 postes ≈ 120 o).
DIGITS = 1

# Préfixe des lignes du démarrage CPU : `boot_cpu:` ne matche rien d'autre dans les
# logs Modal, un `grep '^boot_cpu:'` isole la phase entière.
BOOT_CPU_PREFIX = "boot_cpu:"


def now() -> float:
    """Horloge monotone en secondes (base de ``elapsed_ms`` et des postes)."""
    return time.perf_counter()


def elapsed_ms(started: float) -> float:
    """Temps écoulé depuis ``now()`` en millisecondes, arrondi à la décimale utile."""
    return round((time.perf_counter() - started) * 1000.0, DIGITS)


def add_ms(timers: dict, stage: str, value_ms: float) -> float:
    """Ajoute une durée déjà mesurée au poste ``stage`` (cumul) et renvoie le cumul."""
    total = round(timers.get(stage, 0.0) + float(value_ms), DIGITS)
    timers[stage] = total
    return total


class Stage:
    """Chronomètre de bloc : ``with Stage(timers, "vision_ms"):``.

    Le temps est enregistré **même si le bloc lève** (``__exit__`` ne masque jamais
    l'exception, il renvoie ``False``) : la trace d'une frame en échec reste lisible.
    """

    __slots__ = ("_timers", "_stage", "_started")

    def __init__(self, timers: dict, stage: str) -> None:
        self._timers = timers
        self._stage = stage
        self._started = 0.0

    def __enter__(self) -> "Stage":
        self._started = now()
        return self

    def __exit__(self, *_exc) -> bool:
        add_ms(self._timers, self._stage, elapsed_ms(self._started))
        return False


def _duration(value) -> str | None:
    """``31`` -> ``"31.0"``, ``4.25`` -> ``"4.2"`` ; une valeur non numérique -> None (ignorée)."""
    try:
        return f"{float(value):.{DIGITS}f}"
    except (TypeError, ValueError):
        return None


def format_server_timing(timings: dict | None) -> str:
    """En-tête ``Server-Timing`` : ``preprocess_ms;dur=1.2, vision_ms;dur=31.0``.

    Les noms de postes sont **ceux de ``timings_ms``** (suffixe ``_ms`` compris) : le banc
    et l'écran n'ont qu'une seule convention à connaître. Ordre = ordre d'insertion de la
    carte (déterministe, donc comparable entre deux frames). Carte vide ou absente → chaîne
    vide : l'appelant n'émet alors **aucun** en-tête (le client lit l'absence du champ).
    """
    if not timings:
        return ""
    parts: list[str] = []
    for stage, value in timings.items():
        duration = _duration(value)
        if duration is not None:
            parts.append(f"{stage};dur={duration}")
    return ", ".join(parts)


def format_boot_cpu(stage: str, ms: float, total_ms: float) -> str:
    """Ligne de log d'un étage du boot CPU (durée + cumul depuis le début, en ms).

    ``boot_cpu: stage=import_torch ms=1200.5 total_ms=1200.5`` — même décimale que
    les postes ``Server-Timing`` (:data:`DIGITS`), préfixe :data:`BOOT_CPU_PREFIX`.
    """
    return (f"{BOOT_CPU_PREFIX} stage={stage} ms={ms:.{DIGITS}f} "
            f"total_ms={total_ms:.{DIGITS}f}")


def container_age_seconds():
    """Âge du conteneur en secondes (uptime machine - début du processus).

    Après une restauration de snapshot, le processus est neuf mais ``/proc`` voit
    son vrai âge — là où le chrono du code ne verrait que la phase GPU rejouée.
    """
    try:
        with open("/proc/uptime", "r", encoding="utf-8") as handle:
            uptime = float(handle.read().split()[0])
        with open("/proc/self/stat", "r", encoding="utf-8") as handle:
            fields = handle.read().rsplit(") ", 1)[1].split()
        start_ticks = int(fields[19])
        hertz = os.sysconf("SC_CLK_TCK")
        return round(uptime - start_ticks / hertz, 1)
    except Exception:  # noqa: BLE001 — mesure best-effort, jamais bloquante
        return None
