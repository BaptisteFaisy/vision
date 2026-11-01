"""Pré-traitement fusionné (``FAST_PREPROCESS``) : levier, replis, équivalence HF.

Sans stack lourde (le cas de ``test_units.py`` : stdlib + PIL + numpy), seuls le
levier, la matrice de repli et la validation d'entrée sont exercés — avec des
préprocesseurs factices, jamais de réseau. Avec torch (+ transformers et
torchvision pour la référence), ``pixels_fast`` est comparé au préprocesseur
SAM 3.1 construit en direct (aucun poids, aucun Hub) sur images synthétiques.
"""

from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path
from types import SimpleNamespace

LIVE_FAST_DIR = Path(__file__).resolve().parents[2] / "backend" / "live_fast"
# 2 LSB 8 bits en unités normalisées (std=0,5) : le chemin rapide resize en
# float32 contre uint8 côté HF — mesuré ≤ 7,87e-3 sur 96 cas adversariaux.
TOLERANCE = 1.6e-2


def _fresh(module_name: str, preprocess: str | None = None):
    """Module déployé importé à neuf, ``FAST_PREPROCESS`` maîtrisé (None = absent)."""
    previous = os.environ.get("FAST_PREPROCESS")
    if preprocess is None:
        os.environ.pop("FAST_PREPROCESS", None)
    else:
        os.environ["FAST_PREPROCESS"] = preprocess
    sys.path.insert(0, str(LIVE_FAST_DIR))
    sys.modules.pop("weights", None)
    try:
        spec = importlib.util.spec_from_file_location(f"live_fast_{module_name}_preproc",
                                                      LIVE_FAST_DIR / f"{module_name}.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        sys.path.remove(str(LIVE_FAST_DIR))
        if previous is None:
            os.environ.pop("FAST_PREPROCESS", None)
        else:
            os.environ["FAST_PREPROCESS"] = previous


def _real_torch():
    """Vrai torch, ou None (absent — ou stub d'un groupe précédent, non-paquet)."""
    try:
        import torch.nn.functional  # noqa: F401
        import torch
    except ImportError:
        return None
    return torch


def _processor(**overrides):
    """Préprocesseur factice « config servie » (carré 518 bilinéaire + norm)."""
    params = {"size": {"height": 518, "width": 518}, "resample": 2,
              "do_resize": True, "do_rescale": True, "do_normalize": True,
              "image_mean": (0.5, 0.5, 0.5), "image_std": (0.5, 0.5, 0.5)}
    params.update(overrides)
    return SimpleNamespace(image_processor=SimpleNamespace(**params))


def test_preprocess_lever(_module) -> None:
    """Levier ``FAST_PREPROCESS`` : défaut hf, valeur exposée, repli total sans torch."""
    from test_units import check

    default = _fresh("fast_core")
    check("preproc : défaut hf (chemin historique inchangé)",
          default.PREPROCESS == "hf", repr(default.PREPROCESS))
    check("preproc : levier exposé dans levers()",
          default.levers().get("preprocess") == "hf", str(default.levers().get("preprocess")))
    check("preproc : FAST_PREPROCESS=fast lu à l'import",
          _fresh("fast_core", "fast").PREPROCESS == "fast")
    check("preproc : valeur blanchie (casse/espaces)",
          _fresh("fast_core", "  FAST ").PREPROCESS == "fast")

    fast = _fresh("fast_core", "fast")
    ctx = {"processor": _processor(), "device": "cpu", "dtype": None}
    if _real_torch() is None:
        check("preproc : sans torch, repli HF (None)", fast._fast_pixels(ctx, None) is None)
    else:
        check("preproc : torch présent — chemin réel couvert par preproc_pixels",
              True, "voir banc /tmp + test d'équivalence")
    check("preproc : levier hf → repli même avec config valide",
          default._fast_pixels(ctx, None) is None)


def test_preprocess_fallback(_module) -> None:
    """Toute config hors support retombe sur l'appel HF, jamais d'exception."""
    from test_units import check

    fast = _fresh("fast_core", "fast")
    base = {"device": "cpu", "dtype": None}
    cases = {
        "préprocesseur factice sans image_processor":
            {"processor": SimpleNamespace()},
        "préprocesseur absent": {"processor": None},
        "taille non carrée": {"processor": _processor(size={"height": 518, "width": 256})},
        "taille absente": {"processor": _processor(size={})},
        "taille non dict": {"processor": _processor(size=518)},
        "resample non bilinéaire": {"processor": _processor(resample=3)},
        "resample inconnu": {"processor": _processor(resample=None)},
        "do_resize=False": {"processor": _processor(do_resize=False)},
        "do_normalize=False": {"processor": _processor(do_normalize=False)},
        "mean absente": {"processor": _processor(image_mean=None)},
    }
    for name, extra in cases.items():
        ctx = dict(base)
        ctx.update(extra)
        try:
            got = fast._fast_pixels(ctx, None)
        except Exception as exc:  # noqa: BLE001 — le repli ne doit jamais lever
            check(f"preproc : repli {name}", False, f"{type(exc).__name__}: {exc}")
        else:
            check(f"preproc : repli {name}", got is None, repr(got)[:80])


def test_preprocess_wiring(_module) -> None:
    """``_preprocess`` appelle le chemin HF quand le rapide se désiste (sentinelle)."""
    from test_units import check

    from PIL import Image

    fast = _fresh("fast_core", "fast")
    sentinel = object()
    calls = []

    def fake_processor(images=None, return_tensors=None):
        calls.append((images.size, return_tensors))
        return {"pixel_values": sentinel}

    fake_processor.image_processor = None
    ctx = {"processor": fake_processor, "device": "cpu", "dtype": None}
    image = Image.new("RGB", (512, 384), (10, 200, 30))
    work, pixels = fast._preprocess(ctx, image, 512)
    check("preproc : _preprocess garde le resize historique",
          work.size == (512, 384), str(work.size))
    check("preproc : _preprocess retombe sur l'appel HF",
          pixels is sentinel and calls == [((512, 384), "pt")], str(calls))

    imaging = _fresh("imaging")
    check("preproc : pixels_fast refuse un edge nul",
          _raises(lambda: imaging.pixels_fast(image, 0), ValueError))
    if _real_torch() is None:
        check("preproc : pixels_fast exige torch (ImportError sans stack)",
              _raises(lambda: imaging.pixels_fast(image, 64), ImportError))


def _raises(callable_, error_type) -> bool:
    try:
        callable_()
    except error_type:
        return True
    except Exception:  # noqa: BLE001 — mauvaise erreur levée
        return False
    return False


def _stack():
    """Modules lourds optionnels (None si absents — le test reste vert)."""
    torch = _real_torch()
    if torch is None:
        return None
    try:
        import torchvision  # noqa: F401
        from transformers.models.sam3.image_processing_sam3 import Sam3ImageProcessor
    except ImportError:
        return None
    return torch, Sam3ImageProcessor


def _synthetic():
    """Batterie synthétique 512×384 : bruit, plage, damier (numpy + PIL)."""
    import numpy as np
    from PIL import Image

    rng = np.random.default_rng(5)
    checker = (np.indices((384, 512)).sum(axis=0) % 2 * 255).astype(np.uint8)
    return {"rand": Image.fromarray(rng.integers(0, 256, (384, 512, 3), dtype=np.uint8)),
            "flat": Image.fromarray(np.full((384, 512, 3), 128, np.uint8)),
            "checker": Image.fromarray(np.stack([checker] * 3, axis=-1))}


def test_preprocess_pixels(_module) -> None:
    """``pixels_fast`` : forme, déterminisme, conversions (torch requis)."""
    from test_units import check

    imaging = _fresh("imaging")
    torch = _real_torch()
    if torch is None:
        check("preproc : pixels — torch absent, non exercé ici (banc /tmp)", True)
        return
    image = _synthetic()["rand"]
    out = imaging.pixels_fast(image, 518)
    check("preproc : pixels (1, 3, edge, edge) float32 CPU",
          tuple(out.shape) == (1, 3, 518, 518) and out.dtype == torch.float32
          and not out.is_cuda, f"{tuple(out.shape)} {out.dtype}")
    check("preproc : pixels déterministes",
          bool((out == imaging.pixels_fast(image, 518)).all()), "deux appels divergent")
    gray = imaging.pixels_fast(image.convert("L"), 64)
    check("preproc : entrée grise convertie en RGB",
          tuple(gray.shape) == (1, 3, 64, 64), str(tuple(gray.shape)))
    picked = imaging.pixels_fast(image, 64, device="cuda")
    check("preproc : device cuda honoré si GPU, sinon repli CPU",
          picked.is_cuda == torch.cuda.is_available(), str(picked.device))
    if torch.cuda.is_available():
        gpu = imaging.pixels_fast(image, 518, device="cuda", dtype=torch.bfloat16)
        check("preproc : pixels GPU dans le dtype modèle",
              gpu.is_cuda and gpu.dtype == torch.bfloat16 and tuple(gpu.shape) == (1, 3, 518, 518),
              f"{gpu.device} {gpu.dtype}")


def test_preprocess_equivalence(_module) -> None:
    """``pixels_fast`` ≡ préprocesseur SAM 3.1 à 2 LSB près (stack complète)."""
    from test_units import check

    imaging = _fresh("imaging")
    stack = _stack()
    if stack is None:
        check("preproc : équivalence HF — stack lourde absente (banc /tmp)", True)
        return
    _, Sam3ImageProcessor = stack
    reference = Sam3ImageProcessor(size={"height": 518, "width": 518},
                                   image_mean=[0.5, 0.5, 0.5],
                                   image_std=[0.5, 0.5, 0.5])
    worst, arg = 0.0, None
    for name, pil in _synthetic().items():
        expected = reference(images=pil, return_tensors="pt")["pixel_values"]
        diff = (imaging.pixels_fast(pil, 518) - expected).abs().max().item()
        if diff > worst:
            worst, arg = diff, name
    check(f"preproc : équivalence HF ≤ {TOLERANCE} (pire {worst:.3e} sur {arg})",
          worst <= TOLERANCE, f"{worst:.3e} sur {arg}")


def test_preprocess_deploy(_module) -> None:
    """Le levier est recuit dans l'env de l'image déployée (surchageable au deploy)."""
    from test_units import check

    app = _fresh("live_app_fast")
    check("preproc : FAST_PREPROCESS dans LEVER_NAMES",
          "FAST_PREPROCESS" in app.LEVER_NAMES, str(app.LEVER_NAMES))
    check("preproc : lever_env() transmet le levier",
          "FAST_PREPROCESS" in app.lever_env(), "absent de lever_env()")
