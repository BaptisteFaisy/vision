"""Tests du chemin officiel natif (sans GPU) : ``backend/segmentation_native.py``.

Sorties multiplex factices (numpy) + faux predictor : le mapping vers le
contrat SPEC, le choix des concepts et la récupération des poids sont exercés
sans torch ni GPU.
"""

import base64
import io
import os
import sys


def _fake_outputs(empty=False):
    """Sorties ``add_prompt`` factices (numpy, deux objets triés 0.8 > 0.3)."""
    import numpy as np

    if empty:
        return {"out_obj_ids": np.array([])}
    return {
        "out_obj_ids": np.array([4, 9]),
        "out_boxes_xywh": np.array([[0.0, 0.0, 0.5, 0.5], [0.5, 0.5, 0.5, 0.5]]),
        "out_probs": np.array([0.8, 0.3]),
        "out_binary_masks": [np.ones((6, 6), dtype=bool),
                             np.zeros((6, 6), dtype=bool)],
    }


class FakePredictor:
    """Predictor multiplex factice : enregistre les prompts, rend ``outputs``."""

    def __init__(self, outputs):
        self.outputs = outputs
        self.texts: list[str] = []
        self.opened = 0
        self.closed = 0

    def handle_request(self, request):
        kind = request["type"]
        if kind == "start_session":
            self.opened += 1
            assert request["resource_path"][1] == 1, "session d'une seule frame"
            return {"session_id": f"s{self.opened}"}
        if kind == "add_prompt":
            assert request["frame_index"] == 0
            self.texts.append(request["text"])
            return {"outputs": dict(self.outputs)}
        if kind == "close_session":
            self.closed += 1
            return {}
        raise AssertionError(f"requête inattendue : {kind}")


def _imports_light(module) -> bool:
    """``segmentation_native`` s'importe seul, sans torch ni sam3 (sous-processus)."""
    import subprocess
    from pathlib import Path

    backend = Path(module.__file__).resolve().parent
    code = ("import sys; sys.path.insert(0, %r); import segmentation_native; "
            "print('torch' in sys.modules or 'sam3' in sys.modules)" % str(backend))
    done = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True,
                          timeout=60)
    return done.returncode == 0 and done.stdout.strip() == "False"


def test_native_source(module):
    """Le chemin natif pointe le dépôt officiel et son volume dédié."""
    from test_units import check

    check("natif : dépôt officiel", module.OFFICIAL_REPO == "facebook/sam3.1",
          module.OFFICIAL_REPO)
    check("natif : checkpoint multiplex + config",
          tuple(module.CKPT_FILES) == ("sam3.1_multiplex.pt", "config.json"),
          repr(module.CKPT_FILES))
    check("natif : secret HF", module.HF_SECRET_NAME == "huggingface",
          module.HF_SECRET_NAME)
    check("natif : volume dédié", module.VOLUME_NAME == "sam3-official",
          module.VOLUME_NAME)
    check("natif : pas de stack lourde à l'import", _imports_light(module),
          "torch/sam3 importés par segmentation_native")


def test_native_image(module):
    """L'image embarque le paquet natif + ses dépendances (sinon crash au boot)."""
    from test_units import check

    source = open(module.__file__, encoding="utf-8").read()
    for dep in ("facebookresearch/sam3.git", "einops", "pycocotools", "hydra-core",
                "opencv-python-headless", "setuptools<80"):
        check(f"natif : image → {dep}", dep in source, "absent de modal_app.py")
    check("natif : secret huggingface branché",
          "from_name(HF_SECRET_NAME)" in source, "")
    check("natif : volume des poids branché",
          "Volume.from_name(VOLUME_NAME" in source, "")


def test_native_fetch(module):
    """Poids déjà là : 0 s, aucun import réseau ; sinon HF_TOKEN exigé."""
    import tempfile

    from test_units import check, raises

    saved_dir, saved_token = module.CKPT_DIR, os.environ.pop("HF_TOKEN", None)
    try:
        with tempfile.TemporaryDirectory() as tmp:
            module.CKPT_DIR = tmp
            check("natif : tout manque quand vide",
                  module.missing_files() == list(module.CKPT_FILES), "")
            check("natif : HF_TOKEN exigé quand il manque",
                  raises(module.fetch_checkpoint, OSError), "")
            for name in module.CKPT_FILES:
                open(os.path.join(tmp, name), "wb").close()
            check("natif : rien ne manque quand plein", module.missing_files() == [], "")
            check("natif : 0 s sans réseau", module.fetch_checkpoint() == 0.0, "")
            check("natif : hub non importé",
                  "huggingface_hub" not in sys.modules, "")
    finally:
        module.CKPT_DIR = saved_dir
        if saved_token is not None:
            os.environ["HF_TOKEN"] = saved_token


def test_native_entries(module):
    """Sorties multiplex -> entrées : label intact, score, boîte bornée, masque brut."""
    import numpy as np

    from test_units import check

    entries = module.native_entries(_fake_outputs(), "Chaise", (10, 8))
    check("natif : deux entrées", len(entries) == 2, repr(entries)[:200])
    first = entries[0]
    check("natif : label tel que saisi", first["label"] == "Chaise", first["label"])
    check("natif : score arrondi", first["score"] == 0.8, repr(first["score"]))
    x1, y1, x2, y2 = first["box"]
    check("natif : boîte ordonnée et bornée",
          0 <= x1 <= x2 <= 10 and 0 <= y1 <= y2 <= 8, repr(first["box"]))
    check("natif : masque brut préservé",
          isinstance(first["mask"], np.ndarray) and first["mask"].shape == (6, 6),
          repr(getattr(first["mask"], "shape", None)))
    check("natif : vide sans ids",
          module.native_entries(_fake_outputs(empty=True), "x", (10, 8)) == [], "")


def _mask_size_png(payload: str):
    """Taille du PNG décodé depuis un ``mask_b64``."""
    from PIL import Image

    with Image.open(io.BytesIO(base64.b64decode(payload))) as decoded:
        return decoded.size


def test_native_flow(module):
    """Bout en bout factice : concepts EN au modèle, contrat SPEC au client."""
    from PIL import Image

    import segmentation
    from test_units import check

    image = Image.new("RGB", (12, 9), (10, 20, 30))
    predictor = FakePredictor(_fake_outputs())
    payload = module.run_segmentation_native(predictor, image, ["Chaise", "chien"])
    check("natif : concepts anglais au modèle", predictor.texts == ["chair", "dog"],
          repr(predictor.texts))
    check("natif : sessions refermées",
          predictor.opened == 2 and predictor.closed == 2,
          f"{predictor.opened}/{predictor.closed}")
    check("natif : dimensions", (payload["width"], payload["height"]) == (12, 9),
          repr({k: payload.get(k) for k in ("width", "height")}))
    objects = payload["objects"]
    check("natif : tri par score décroissant",
          [item["score"] for item in objects] == sorted(
              [item["score"] for item in objects], reverse=True),
          repr([item["score"] for item in objects]))
    check("natif : labels tels que saisis",
          {item["label"] for item in objects} == {"Chaise", "chien"},
          repr([item["label"] for item in objects]))
    check("natif : masques PNG à la taille exacte",
          all(_mask_size_png(item["mask_b64"]) == (12, 9) for item in objects), "")
    check("natif : boîtes dans l'image",
          all(0 <= item["box"][0] <= item["box"][2] <= 12
              and 0 <= item["box"][1] <= item["box"][3] <= 9 for item in objects), "")
    vacant = FakePredictor(_fake_outputs(empty=True))
    module.run_segmentation_native(vacant, image, None)
    check("natif : défauts puis repli quand vide",
          vacant.texts == list(segmentation.DEFAULT_PROMPTS)
          + list(segmentation.FALLBACK_PROMPTS), repr(vacant.texts))


def test_analyze_shape(module):
    """Payload /analyze réel (faux predictor) : description, tags, dimensions, objets."""
    import threading

    import llm
    from test_units import check, sample_png

    service = module.Sam3Service()
    service.predictor = FakePredictor(_fake_outputs())
    service._gpu_lock = threading.Lock()
    saved_key, saved_describe = os.environ.get("LLM_API_KEY"), llm.describe_image
    os.environ["LLM_API_KEY"] = "test"
    llm.describe_image = lambda *args: ("une description", ["tag"])
    try:
        payload = service._analyze_sync(sample_png())
    finally:
        llm.describe_image = saved_describe
        if saved_key is None:
            os.environ.pop("LLM_API_KEY", None)
        else:
            os.environ["LLM_API_KEY"] = saved_key
    check("/analyze : description + tags",
          payload.get("description") == "une description" and payload.get("tags") == ["tag"],
          str(payload)[:160])
    check("/analyze : width/height entiers",
          payload.get("width") == 64 and payload.get("height") == 40,
          repr({k: payload.get(k) for k in ("width", "height")}))
    objects = payload.get("objects")
    check("/analyze : objets non vides", isinstance(objects, list) and len(objects) > 0,
          repr(objects)[:160])
    check("/analyze : boîtes dans l'image",
          all(0 <= item["box"][0] <= item["box"][2] <= 64
              and 0 <= item["box"][1] <= item["box"][3] <= 40 for item in objects), "")
