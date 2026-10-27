"""Amorçage Baseten du service SAM 3.1 live (uvicorn sur l'image Truss).

Baseten lance ce module avec ``uvicorn server:app`` (``docker_server.start_command``) ;
le modèle se charge au démarrage via le lifespan de l'app (``ensure_loaded`` dans
``service.py``), donc ``GET /health`` ne répond « prêt » qu'une fois la carte chargée.
Aucune logique métier ici : tout est dans ``service.py``, partagé avec Modal.
"""

import os

os.environ.setdefault("BACKEND_PLATFORM", "baseten")

from service import Sam3LiveService  # noqa: E402 — l'env doit être posé avant l'import

_service = Sam3LiveService()
app = _service.api()