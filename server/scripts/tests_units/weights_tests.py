"""Tests des **poids embarqués dans l'image** (apport fin octobre, `FAST_MODEL_DIR`).

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
LIVE_FAST_DIR = BACKEND_DIR / "live_fast"


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
    """Le script de déploiement porte les leviers de mesure et le scaledown court (60 s)."""
    from test_units import check, raises

    script = (BACKEND_DIR.parent / "scripts" / "deploy-live-fast.sh").read_text(encoding="utf-8")
    # Scaledown 60 (fuite max ~0,04 $ si /release raté, règle user 27/10).
    for lever in ("FAST_TIMING=1", "FAST_SCALEDOWN=60", "FAST_BATCH_PROMPTS=1",
                  "FAST_EDGE=512", "FAST_SNAPSHOT=1", "FAST_MODEL_DIR=/opt/models/sam3.1",
                  "FAST_ATTN=sdpa"):
        check(f"déploiement : {lever} présent", lever in script, "absent du script")
    check("déploiement : FAST_DETR_GRAPH éteint (rollback crash replay)",
          "FAST_DETR_GRAPH=1" not in script, "levier réactivé sans diagnostic")
    # GPU et nom d'app passent par des variables (`${FAST_GPU:-...}`) : on fige
    # l'intention (variable + valeur déployée), pas une affectation littérale.
    check("déploiement : GPU A100-40GB par défaut",
          "FAST_GPU" in script and "A100-40GB" in script, "absent du script")
    check("déploiement : app sam3-eu-a100 par défaut",
          "LIVE_APP_NAME" in script and "sam3-eu-a100" in script, "absent du script")
    check("déploiement : le contrôle exige le snapshot",
          '"snapshot": True' in script, "absent du contrôle")
    check("déploiement : le contrôle exige restored",
          "champ restored absent" in script, "absent du contrôle")
    check("déploiement : le contrôle exige load_breakdown",
          "champ load_breakdown absent" in script, "absent du contrôle")
    check("déploiement : le contrôle acte le boot (restore vs complet)",
          'print("boot   :"' in script, "absent du contrôle")
    check("déploiement : 500/injoignable = retry transparent",
          "fenêtre de mort" in script, "absent du script")


def test_no_cudagraph_typo(_module) -> None:
    """Pas de `CUDGraph` (sans A) : la faute a cassé vision_accel au déploiement du 27/10."""
    from test_units import check

    src = (BACKEND_DIR / "live_fast" / "fast_core.py").read_text(encoding="utf-8")
    check("graphe : CUDAGraph orthographié", "CUDAGraph" in src, "absent")
    check("graphe : pas de CUDGraph", "CUDGraph" not in src, "faute présente")


def test_concepts_sync(_module) -> None:
    """La table FR→EN déployée (live_fast) est la même qu'à la racine."""
    from test_units import check

    import concepts as root

    deployed = _fresh_live_fast("concepts")
    check("concepts : table déployée == racine",
          deployed.CONCEPT_ALIASES == root.CONCEPT_ALIASES, "divergence live_fast/concepts.py")
    check("concepts : aucune collision après désaccentuation",
          len(deployed._ALIASES_NORM) == len(deployed.CONCEPT_ALIASES),
          "deux clés perdent leur distinction sans accents")


def _fresh_live_fast(module_name: str, model_dir: str | None = "/opt/models/sam3.1",
                     snapshot: str | None = "1"):
    """Module de `live_fast` importé à neuf (déployé), env + path maîtrisés."""
    saved = {key: os.environ.get(key) for key in ("FAST_MODEL_DIR", "FAST_SNAPSHOT")}
    if model_dir is None:
        os.environ.pop("FAST_MODEL_DIR", None)
    else:
        os.environ["FAST_MODEL_DIR"] = model_dir
    if snapshot is None:
        os.environ.pop("FAST_SNAPSHOT", None)
    else:
        os.environ["FAST_SNAPSHOT"] = snapshot
    sys.path.insert(0, str(LIVE_FAST_DIR))
    # `weights` est mis en cache par le premier import : l'expulser pour que
    # chaque cas relise l'environnement (sinon MODEL_SOURCE reste périmé).
    sys.modules.pop("weights", None)
    try:
        spec = importlib.util.spec_from_file_location(f"live_fast_{module_name}_probe",
                                                      LIVE_FAST_DIR / f"{module_name}.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        sys.path.remove(str(LIVE_FAST_DIR))
        for key, value in saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def test_live_fast_weights(_module) -> None:
    """Le module DÉPLOYÉ lit les poids locaux (parité avec la référence)."""
    from test_units import check, raises

    module = _fresh_live_fast("fast_core")
    check("live_fast : MODEL_SOURCE = répertoire de l'image",
          module.MODEL_SOURCE == "/opt/models/sam3.1", module.MODEL_SOURCE)
    check("live_fast : local_only() interdit le réseau",
          module.local_only() == {"local_files_only": True}, str(module.local_only()))
    check("live_fast : leviers exposent le snapshot",
          module.levers().get("snapshot") is True, str(module.levers().get("snapshot")))

    calls: list = []
    with _Patched(calls):
        model, processor, dtype = module.load_cpu("bf16")
    sources = [call[0] for call in calls if isinstance(call, tuple)]
    check("live_fast : load_cpu lit le répertoire local",
          sources == ["/opt/models/sam3.1"] * 2, str(sources))
    check("live_fast : load_cpu sans device (reste CPU jusqu'à `enter`)",
          not [call for call in calls if call[0] == "to"], str(calls))

    calls.clear()
    with _Patched(calls):
        _model, _proc, device, _dtype = module.load("fp32")
    check("live_fast : load() vise le device et garde les 4 valeurs",
          device == "cpu", str(device))

    bare = _fresh_live_fast("fast_core", model_dir=None, snapshot=None)
    check("live_fast : sans levier, source = Hub", bare.MODEL_SOURCE == bare.MODEL_ID,
          bare.MODEL_SOURCE)
    check("live_fast : snapshot éteint par défaut (Baseten honnête)",
          bare.levers().get("snapshot") is False, str(bare.levers().get("snapshot")))


def test_live_fast_image(_module) -> None:
    """Le conteneur DÉPLOYÉ pose les poids, le build et le snapshot."""
    from test_units import check, raises

    app = _fresh_live_fast("live_app_fast")
    check("live_fast : MODEL_DIR par défaut dans l'image",
          app.MODEL_DIR == "/opt/models/sam3.1", app.MODEL_DIR)
    check("live_fast : FAST_MODEL_DIR transmis par lever_env()",
          app.lever_env().get("FAST_MODEL_DIR") == app.MODEL_DIR,
          str(app.lever_env().get("FAST_MODEL_DIR")))
    check("live_fast : FAST_SNAPSHOT recuit dans l'env de l'image",
          "FAST_SNAPSHOT" in app.LEVER_NAMES, str(app.LEVER_NAMES))
    build = app.WEIGHTS_BUILD
    check("live_fast : poids tirés au build (snapshot_download)",
          "snapshot_download" in build and "unsloth/sam3.1" in build, build[:120])
    check("live_fast : snapshot mémoire actif par défaut", app.SNAPSHOT is True,
          str(app.SNAPSHOT))
    check("live_fast : double entrée (snap CPU puis GPU)",
          callable(getattr(app.Sam3Live, "load_weights", None))
          and callable(getattr(app.Sam3Live, "load_container_on_enter", None)),
          "entrée manquante")


def test_live_fast_split(_module) -> None:
    """Deux phases : snapshot CPU réutilisé, chemin complet sans snapshot (Baseten)."""
    from test_units import check, raises

    service = _fresh_live_fast("service")
    calls: list = []

    class _FakeModel:
        def to(self, device):
            calls.append(("to", device))
            return self

        def eval(self):
            return self

    fake = _FakeModel()
    service.load = lambda *a, **k: (calls.append("load"), (fake, "proc", "cpu", "bf16"))[1]
    service.load_cpu = lambda *a, **k: (calls.append("load_cpu"), (fake, "proc", "bf16"))[1]
    service.prepare = lambda *a, **k: {}
    service.vision_grid = lambda _m: 1008
    service.gpu_name = lambda: "TEST-GPU"

    saved_seg = sys.modules.get("segmentation")
    sys.modules["segmentation"] = SimpleNamespace(device_label=lambda: "cpu")
    try:
        snapped = service.Sam3LiveService()
        snapped.load_cpu_weights()
        check("split : la phase snap ne fait que load_cpu", calls == ["load_cpu"], str(calls))
        check("split : état CPU mémorisé", set(snapped.cpu) == {"model", "processor", "dtype"},
              str(getattr(snapped, "cpu", None)))
        snapped.load_container()
        check("split : restauration ne recharge pas les poids", "load" not in calls,
              str(calls))
        check("split : passage GPU après restauration", ("to", "cpu") in calls, str(calls))
        check("split : ctx construit", snapped.ctx["device"] == "cpu"
              and snapped.gpu_name == "TEST-GPU", str(snapped.ctx.get("device")))
        check("split : état CPU consommé", getattr(snapped, "cpu", None) is None, "cpu restant")

        calls.clear()
        bare = service.Sam3LiveService()
        bare.load_container()
        check("split : sans snapshot, chargement complet", calls[:1] == ["load"], str(calls))
        check("split : Baseten obtient un ctx", bare.ctx["device"] == "cpu", "pas de ctx")
    finally:
        if saved_seg is None:
            sys.modules.pop("segmentation", None)
        else:
            sys.modules["segmentation"] = saved_seg