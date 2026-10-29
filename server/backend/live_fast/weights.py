"""Poids SAM 3.1 : source (Hub ou image), chargement CPU puis GPU.

Extrait de ``fast_core.py`` (limite 500 lignes/fichier) : tout ce qui touche aux
poids vit ici — la source, le dtype, la grille de patchs et les deux chargements :

* ``load_cpu`` — poids + construction sur **CPU** : la phase que Modal fige dans
  le snapshot mémoire (aucun état CUDA, sinon Modal refuse le snapshot) ;
* ``load`` — ``load_cpu`` + passage sur le device : le chemin historique, gardé
  pour Baseten (pas de snapshot) et le premier démarrage (pas encore de snapshot).

``FAST_MODEL_DIR`` (posé par ``live_app_fast`` dans l'image, poids téléchargés au
build) : les lectures passent par le répertoire local avec ``local_files_only``,
fini le re-téléchargement des ~1,7 Go à chaque réveil. Absent : Hub historique.

torch / transformers ne sont importés que dans les fonctions : ce module reste
importable et testable sans stack GPU.
"""

from __future__ import annotations

import os

from timing import elapsed_ms, format_boot_cpu, now

PATCH = 14
MODEL_ID = "unsloth/sam3.1"
# Poids embarqués dans l'image (FAST_MODEL_DIR) : sans ça chaque démarrage à froid
# retélécharge ~1,7 Go depuis le Hub HF. `local_files_only=True` interdit tout retour réseau.
MODEL_DIR = os.environ.get("FAST_MODEL_DIR", "") or ""
MODEL_SOURCE = MODEL_DIR or MODEL_ID
DTYPE_NAME = (os.environ.get("FAST_DTYPE") or "bf16").strip().lower()
# Implémentation d'attention (`sdpa` conseillé : `eager` matérialise la matrice
# complète, ~55 ms sur le poste `detr_ms`). Levier : FAST_ATTN=sdpa.
ATTN_IMPL = os.environ.get("FAST_ATTN") or ""


def round_edge(edge: int) -> int:
    """Arrondi au multiple de 14 le plus proche : la grille de patchs du ViT l'exige."""
    return max(PATCH, int(round(edge / PATCH)) * PATCH)


def torch_dtype(name: str):
    """``bf16|fp16|fp32`` -> dtype torch ; inconnu -> bf16 (le défaut historical du backend)."""
    import torch

    return {"fp16": torch.float16, "bf16": torch.bfloat16, "fp32": torch.float32}.get(name,
                                                                                       torch.bfloat16)


def _set_attn(model, impl: str) -> str:
    """Force l'implémentation d'attention ; renvoie ce qui a réellement été appliqué."""
    if not impl:
        return getattr(getattr(model, "config", None), "_attn_implementation", "?")
    try:
        model.set_attn_implementation(impl)
    except Exception:  # noqa: BLE001 — version sans set_attn_implementation
        pass
    # Chaque sous-module d'attention lit **son propre** `config._attn_implementation`
    # (le config racine n'est pas le même) : il faut donc balayer les modules. Sans ça,
    # l'attention reste en `eager` — la matrice complète est matérialisée, d'où les
    # ~55 ms du poste `detr_ms`.
    touched = 0
    for module in model.modules():
        cfg = getattr(module, "config", None)
        if cfg is None or not hasattr(cfg, "_attn_implementation"):
            continue
        try:
            cfg._attn_implementation = impl
            touched += 1
        except Exception:  # noqa: BLE001
            pass
    root = getattr(model, "config", None)
    if root is not None:
        try:
            root._attn_implementation = impl
        except Exception:  # noqa: BLE001
            pass
    applied = getattr(root, "_attn_implementation", "?")
    return applied if applied == impl else f"failed:{applied}:touched={touched}"


def local_only() -> dict:
    """Poids déjà dans l'image -> pas de réseau au chargement (rapide, déterministe)."""
    return {"local_files_only": True} if MODEL_DIR else {}


def _boot_log(booted: float, stage: str, started: float) -> float:
    """Logge l'étage ``stage`` (durée + cumul depuis ``booted``) ; renvoie le top suivant."""
    print(format_boot_cpu(stage, elapsed_ms(started), elapsed_ms(booted)), flush=True)
    return now()


def load(dtype_name: str = DTYPE_NAME, edge=None):
    """Charge SAM 3.1 avec le dtype demandé ; retourne (model, processor, device, dtype).

    ``edge`` (< 1008) doit être injecté **dans la config avant la construction du
    modèle** : le RoPE 2D du ViT est pré-calculé à l'init (grille
    ``image_size / patch_size`` = 72x72 pour 1008 px) et ne se recalcule pas tout seul.
    Un simple ``processor.image_processor.size`` suffit au préprocesseur mais fait
    planter l'attention globale (256 tokens contre une table de 5184).
    """
    import torch
    from segmentation import device_label
    from transformers import Sam3Model, Sam3Processor

    device = device_label()
    dtype = torch_dtype(dtype_name if device == "cuda" else "fp32")
    kwargs = {}
    if edge:
        from transformers import Sam3Config

        config = Sam3Config.from_pretrained(MODEL_SOURCE, **local_only())
        config.vision_config.backbone_config.image_size = round_edge(int(edge))
        kwargs["config"] = config
    model = Sam3Model.from_pretrained(MODEL_SOURCE, dtype=dtype, **kwargs).to(device).eval()
    _set_attn(model, ATTN_IMPL)
    processor = Sam3Processor.from_pretrained(MODEL_SOURCE, **local_only())
    return model, processor, device, dtype


def load_cpu(dtype_name: str = DTYPE_NAME, edge=None):
    """Poids chargés **sur CPU** : la partie du démarrage qui peut être snapshottée.

    Un snapshot mémoire Modal ne contient pas d'état GPU (contexte CUDA, allocations).
    D'où la coupure : lecture des 1,7 Go et construction du graphe sur CPU ici, puis
    ``.to("cuda")`` dans une méthode `enter` normale, après restauration du snapshot.
    Retourne (model, processor, dtype) — le device n'est pas encore choisi.

    Chaque étage logge une ligne ``boot_cpu:`` (durée + cumul) : c'est ce qui tranche
    la répartition des ~15 s de phase CPU (imports ? lecture des poids ? autre ?).
    """
    booted = now()
    top = now()
    import torch
    top = _boot_log(booted, "import_torch", top)
    from transformers import Sam3Config, Sam3Model, Sam3Processor
    top = _boot_log(booted, "import_transformers", top)

    dtype = torch_dtype(dtype_name)
    kwargs = {}
    if edge:
        top = now()
        config = Sam3Config.from_pretrained(MODEL_SOURCE, **local_only())
        config.vision_config.backbone_config.image_size = round_edge(int(edge))
        kwargs["config"] = config
        top = _boot_log(booted, "config_edge", top)
    model = Sam3Model.from_pretrained(MODEL_SOURCE, dtype=dtype, **kwargs).eval()
    top = _boot_log(booted, "model_from_pretrained", top)
    processor = Sam3Processor.from_pretrained(MODEL_SOURCE, **local_only())
    _boot_log(booted, "processor_from_pretrained", top)
    _boot_log(booted, "total", booted)
    return model, processor, dtype
