"""Tests des **poids embarqués dans l'image** (apport du 25/10 20:30, `FAST_MODEL_DIR`).

Ces tests figent ce que la fusion doit préserver : sans `FAST_MODEL_DIR`, `fast_core`
garde le comportement historique (Hub, `unsloth/sam3.1`) ; avec, toutes les lectures
passent par le répertoire local avec `local_files_only=True` — et `live_app_fast` pose ce
répertoire dans l'environnement de l'image, télécharge les poids au build et active le
snapshot mémoire. Aucun réseau, aucune GPU : `torch`/`transformers` sont des doublures.

`fast_core` lit `FAST_MODEL_DIR` **à l'import** : chaque cas charge donc un module neuf
(`spec_from_file_location`) plutôt que de dépendre de l'ordre des groupes de tests.
"""

from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path
from types import SimpleNamespace

BACKEND_DIR = Path(__file__).resolve().parents[2] / "backend"


def _fresh_fast_core(model_dir: str | None):
    """`fast_core` importé à neuf, avec `FAST_MODEL_DIR` positionné (None = absent)."""
    previous = os.environ.get("FAST_MODEL_DIR")
    if model_dir is None:
        os.environ.pop("FAST_MODEL_DIR", None)
    else:
        os.environ["FAST_MODEL_DIR"] = model_dir
    try:
        spec = importlib.util.spec_from_file_location("fast_core_weights_probe",
                                                      BACKEND_DIR / "fast_core.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        if previous is None:
            os.environ.pop("FAST_MODEL_DIR", None)
        else:
            os.environ["FAST_MODEL_DIR"] = previous


class _Patched:
    """Remplace `torch` et `transformers` le temps d'un appel, puis restaure `sys.modules`."""

    def __init__(self, calls: list) -> None:
        self.calls = calls

    def _model(self):
        calls = self.calls

        class _Built:
            def to(self, device):
                calls.append(("to", device))
                return self

            def eval(self):
                return self

        class _Factory:
            @staticmethod
            def from_pretrained(source, **kwargs):
                calls.append((source, kwargs))
                return _Built()

        return _Factory

    def __enter__(self):
        self.saved = {name: sys.modules.get(name) for name in ("torch", "transformers")}
        factory = self._model()
        sys.modules["torch"] = SimpleNamespace(
            bfloat16="bf16", float16="fp16", float32="fp32",
            cuda=SimpleNamespace(is_available=lambda: False, get_device_name=lambda _i: "cpu"))
        sys.modules["transformers"] = SimpleNamespace(
            Sam3Model=factory, Sam3Processor=factory,
            Sam3Config=SimpleNamespace(from_pretrained=factory.from_pretrained))
        return self

    def __exit__(self, *_exc):
        for name, module in self.saved.items():
            if module is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module
        return False


def _config_of(calls: list) -> SimpleNamespace:
    """La config `edge` doit pouvoir recevoir `image_size` sans exploser avant l'appel."""
    return SimpleNamespace(vision_config=SimpleNamespace(backbone_config=SimpleNamespace()))


def test_weights_default(fast_core) -> None:
    """Sans levier : comportement historique intact (Hub, pas de `local_files_only`)."""
    from test_units import check, raises

    module = _fresh_fast_core(None)
    check("poids : sans FAST_MODEL_DIR, source = Hub", module.MODEL_SOURCE == module.MODEL_ID,
          module.MODEL_SOURCE)
    check("poids : sans FAST_MODEL_DIR, local_only() vide", module.local_only() == {},
          str(module.local_only()))
    check("poids : MODEL_ID inchangé", module.MODEL_ID == "unsloth/sam3.1", module.MODEL_ID)


def test_weights_local(fast_core) -> None:
    """Avec le levier : source locale et `local_files_only=True` sur chaque lecture."""
    from test_units import check, raises

    module = _fresh_fast_core("/opt/models/sam3.1")
    check("poids : MODEL_SOURCE = répertoire de l'image",
          module.MODEL_SOURCE == "/opt/models/sam3.1", module.MODEL_SOURCE)
    check("poids : local_only() interdit le réseau",
          module.local_only() == {"local_files_only": True}, str(module.local_only()))

    calls: list = []
    with _Patched(calls):
        model, processor, dtype = module.load_cpu("bf16")
    sources = [call[0] for call in calls if isinstance(call, tuple)]
    check("poids : load_cpu lit le répertoire local", sources == ["/opt/models/sam3.1"] * 2, str(sources))
    # Le modèle est construit depuis un CHEMIN local (aucun réseau possible) ; c'est le
    # processeur — qui résout des fichiers annexes — qui reçoit explicitement le drapeau.
    check("poids : le processeur interdit le réseau",
          calls[-1][1].get("local_files_only") is True, str(calls))
    check("poids : load_cpu ne choisit pas le device (reste CPU jusqu'à `enter`)",
          not [call for call in calls if call[0] == "to"], str(calls))

    calls.clear()
    with _Patched(calls):
        model, processor, device, dtype = module.load("fp32")
    check("poids : load() vise le device et garde les 4 valeurs",
          isinstance((model, processor, dtype), tuple) and device == "cpu", str(device))
    check("poids : load() lit aussi le répertoire local",
          [call[0] for call in calls
           if isinstance(call, tuple) and str(call[0]).startswith("/")] == ["/opt/models/sam3.1"] * 2,
          str(calls))


def test_weights_edge(fast_core) -> None:
    """`edge` est injecté dans la config **avant** la construction (RoPE 14 px)."""
    from test_units import check, raises

    module = _fresh_fast_core("/opt/models/sam3.1")
    configs: list = []

    class _Config:
        @staticmethod
        def from_pretrained(source, **kwargs):
            config = _config_of([])
            configs.append((source, kwargs, config))
            return config

    calls: list = []
    with _Patched(calls):
        sys.modules["transformers"].Sam3Config = _Config
        module.load_cpu("bf16", edge=518)
    check("poids : edge -> grille arrondie 14 px",
          int(configs[0][2].vision_config.backbone_config.image_size) == 518, str(configs)[:120])
    check("poids : config lue en local aussi",
          configs[0][0] == "/opt/models/sam3.1" and configs[0][1].get("local_files_only") is True,
          str(configs)[:120])


def test_weights_image(live_app_fast) -> None:
    """Le conteneur pose le répertoire, télécharge au build et active le snapshot."""
    from test_units import check, raises

    check("image : MODEL_DIR par défaut dans l'image",
          live_app_fast.MODEL_DIR == "/opt/models/sam3.1", live_app_fast.MODEL_DIR)
    check("image : FAST_MODEL_DIR transmis par lever_env()",
          live_app_fast.lever_env().get("FAST_MODEL_DIR") == live_app_fast.MODEL_DIR,
          str(live_app_fast.lever_env().get("FAST_MODEL_DIR")))
    build = live_app_fast.WEIGHTS_BUILD
    check("image : les poids sont tirés au build (snapshot_download)",
          "snapshot_download" in build and "unsloth/sam3.1" in build, build[:120])
    check("image : téléchargement dans le répertoire de l'image",
          f"local_dir='{live_app_fast.MODEL_DIR}'" in build, build[:120])
    check("image : snapshot mémoire actif par défaut", live_app_fast.SNAPSHOT is True,
          str(live_app_fast.SNAPSHOT))
    check("image : le snapshot mémoire est un réglage du conteneur",
          live_app_fast.SCALEDOWN >= 5, str(live_app_fast.SCALEDOWN))


def test_deploy_script(_module) -> None:
    """Le script de déploiement porte les leviers de mesure et le scaledown long."""
    from test_units import check, raises

    script = (BACKEND_DIR.parent / "scripts" / "deploy-live-fast.sh").read_text(encoding="utf-8")
    for lever in ("FAST_TIMING=1", "FAST_SCALEDOWN=120", "FAST_BATCH_PROMPTS=1",
                  "FAST_EDGE=512", "FAST_GPU=A100-40GB", "LIVE_APP_NAME=sam3-eu-a100"):
        check(f"déploiement : {lever} présent", lever in script, "absent du script")