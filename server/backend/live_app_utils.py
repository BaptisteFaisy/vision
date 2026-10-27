"""Helpers du conteneur historique ``live_app_fast.py`` (sans état, sans GPU).

Extraits pour la garde taille (< 50 lignes/fonction, < 500/fichier) : le module
d'origine les ré-exporte, donc ``live_app_fast.call`` et les tests unitaires
(``accepts_mask_encoding``) ne changent pas.
"""

import asyncio
import inspect
import os

from http_errors import MAX_DETAIL_CHARS
from timing import format_server_timing


def container_age_seconds():
    """Âge du conteneur en secondes (uptime machine - début du processus).

    Sert à mesurer le VRAI temps d'ouverture : après une restauration de snapshot, le
    `enter(snap=True)` n'est pas rejoué, donc le chrono du code ne voit que la phase GPU.
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


async def call(pipeline, *args, **kwargs):
    """Lance le blocage GPU dans un thread ; mappe les erreurs vers les codes HTTP.

    Point de vérité unique des statuts : ``POST /live`` laisse remonter l'``HTTPException``
    (400 décodage/prompt, 500 interne), ``/live/ws`` l'attrape et en fait un message
    d'erreur — même code, même détail borné, aucune logique dupliquée. ``kwargs`` porte
    ``mask_encoding`` (les arguments optionnels du pipeline ne sont pas positionnels).
    """
    from fastapi import HTTPException
    from imaging import ImageDecodeError, MaskEncodingError
    from segmentation import PromptError

    try:
        return await asyncio.to_thread(pipeline, *args, **kwargs)
    except (ImageDecodeError, MaskEncodingError, PromptError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)[:MAX_DETAIL_CHARS]) from exc
    except Exception as exc:
        raise HTTPException(status_code=500,
                            detail=f"internal error: {exc}"[:MAX_DETAIL_CHARS]) from exc


def accepts_param(pipeline, name: str) -> bool:
    """Le pipeline accepte-t-il le paramètre ``name`` ?

    Les doublures de test de ``/live`` (``scripts/test_live_ws.py``) ont la signature
    historique à six paramètres : on ne leur impose pas les suivants, on les leur demande.
    Même motif que la référence ``accepts_text_cache`` — une vérification par signature,
    pas un ``try/except TypeError`` qui avalerait les vraies erreurs du pipeline.
    """
    try:
        return name in inspect.signature(pipeline).parameters
    except (TypeError, ValueError):  # callable sans signature introspectable
        return False


def accepts_mask_encoding(pipeline) -> bool:
    """Le pipeline accepte-t-il le paramètre ``mask_encoding`` ? (Compat historique.)"""
    return accepts_param(pipeline, "mask_encoding")


def live_response(result):
    """Réponse de ``POST /live`` : le résultat, plus ``Server-Timing`` si les chronos existent.

    En-tête additif (RFC 8673) : le levier ``FAST_TIMING`` reste le seul interrupteur.
    Éteint, ``timings_ms`` est absent et aucun en-tête n'est émis — un client ancien ne
    voit strictement rien changer ; un banc lit les postes sans parser le corps.
    """
    header = format_server_timing(result.get("timings_ms") if isinstance(result, dict) else None)
    if not header:
        return result
    from fastapi.responses import JSONResponse

    return JSONResponse(content=result, headers={"Server-Timing": header})
