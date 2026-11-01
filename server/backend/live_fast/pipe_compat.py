"""Compatibilité pipeline du conteneur « live » : erreurs HTTP et introspection.

Même logique que ``live_app_utils`` (conteneur historique, non embarqué ici) :
le conteneur « live » ne monte que ``live_fast/``, d'où ce module jumeau
plutôt qu'un import hors périmètre déployé.
"""

import asyncio
import inspect

from http_errors import MAX_DETAIL_CHARS


async def call(pipeline, *args, **kwargs):
    """Blocage GPU dans un thread, erreurs mappées vers les codes HTTP.

    Vérité unique des statuts : ``POST /live`` laisse remonter l'``HTTPException``,
    ``/live/ws`` en fait un message d'erreur — même code, même détail borné.
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
    """Le pipeline accepte-t-il le paramètre ``name`` ? (Par signature, pas par essai.)

    Les doublures de test à six paramètres ignorent les suivants : on les leur
    demande au lieu de les leur imposer.
    """
    try:
        return name in inspect.signature(pipeline).parameters
    except (TypeError, ValueError):  # callable sans signature introspectable
        return False
