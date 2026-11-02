"""Compatibilité pipeline du conteneur « live » : erreurs HTTP et introspection.

Même logique que ``live_app_utils`` (conteneur historique, non embarqué ici) :
le conteneur « live » ne monte que ``live_fast/``, d'où ce module jumeau
plutôt qu'un import hors périmètre déployé.

Deux écarts assumés avec le jumeau, tous deux pour la disponibilité du direct :

* ``GpuBusy`` → ``429`` : « occupé » est un signal que le client comprend déjà
  (barème d'erreurs), au lieu d'un ``503`` à 75 s qui ressemble à « lent » ;
* ``slots`` : le thread d'inférence est compté, donc une frame abandonnée qui
  calcule encore reste visible dans ``/health`` (voir ``gpu_guard``).
"""

import asyncio
import inspect

from http_errors import MAX_DETAIL_CHARS


async def call(pipeline, *args, slots=None, **kwargs):
    """Blocage GPU dans un thread, erreurs mappées vers les codes HTTP.

    Vérité unique des statuts : ``POST /live`` laisse remonter l'``HTTPException``,
    ``/live/ws`` en fait un message d'erreur — même code, même détail borné.
    ``slots`` (``gpu_guard.Slots``) compte les threads : un ``await`` annulé ne
    tue pas le thread, le compteur le dit à sa place.
    """
    from fastapi import HTTPException
    from gpu_guard import GpuBusy, tracked
    from imaging import ImageDecodeError, MaskEncodingError
    from segmentation import PromptError

    try:
        return await asyncio.to_thread(tracked, pipeline, slots, args, kwargs)
    except GpuBusy as exc:
        raise HTTPException(status_code=429,
                            detail=f"busy, retry: {exc}"[:MAX_DETAIL_CHARS]) from exc
    except (ImageDecodeError, MaskEncodingError, PromptError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)[:MAX_DETAIL_CHARS]) from exc
    except Exception as exc:
        raise HTTPException(status_code=500,
                            detail=f"internal error: {exc}"[:MAX_DETAIL_CHARS]) from exc


def accepts_param(pipeline, name: str) -> bool:
    """Le pipeline accepte-t-il le paramètre ``name`` ? (Par signature, pas par essai.)

    Les doublures de test à six paramètres ignorent les suivants : on les leur
    demande au lieu de les leur imposer.
    """
    try:
        return name in inspect.signature(pipeline).parameters
    except (TypeError, ValueError):  # callable sans signature introspectable
        return False
