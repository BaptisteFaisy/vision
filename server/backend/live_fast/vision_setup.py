"""Réglages structurels du modèle : grille ViT, résolution, compile, graphe CUDA.

Extrait de ``fast_core.py`` (garde < 500 lignes/fichier) : tout ce qui touche au
modèle UNE fois (au chargement ou sur demande ``edge``), jamais par frame. Les
leviers d'exécution restent dans ``fast_core`` (``prepare``/``use_edge`` appellent
ici) ; ``service.py`` garde ``from fast_core import prepare, vision_grid``.
"""

from __future__ import annotations

from weights import round_edge


def vision_grid(model) -> int:
    """Taille d'entrée pour laquelle le RoPE du ViT a été construit (1008 par défaut)."""
    return int(model.config.vision_config.backbone_config.image_size)


def set_vision_grid(model, edge: int) -> str:
    """Recalcule le RoPE du ViT pour une autre taille d'entrée (grille de patchs).

    Les embeddings de position du ViT sont *tuilés* (et non interpolés) : passer de 1008
    à 448 revient à rogner la table 72x72 — le modèle reste valide mais perd de la
    précision de localisation. À n'utiliser que comme banc de latence.
    """
    import torch

    size = round_edge(int(edge))
    model.config.vision_config.backbone_config.image_size = size
    for module in model.modules():
        if hasattr(module, "precompute_positions") and hasattr(module, "position_ids"):
            module.position_ids = torch.tensor(
                module.precompute_positions(module.config, module.window_size), dtype=torch.long)
    return "ok"


def apply_edge(processor, edge) -> int | None:
    """Surcharge ``image_processor.size`` ET ``processor.target_size`` ; None = inchangé."""
    if not edge:
        return None
    size = round_edge(int(edge))
    processor.image_processor.size = {"height": size, "width": size}
    processor.target_size = size
    return size


def read_edge(processor) -> dict:
    """Résolution d'entrée réellement en vigueur (contrôle visuel du levier FAST_EDGE)."""
    return {"image_processor_size": dict(getattr(processor.image_processor, "size", {}) or {}),
            "target_size": getattr(processor, "target_size", None),
            "mask_size": dict(getattr(processor.image_processor, "mask_size", {}) or {})}


def _warmup(callable_, device: str, dtype, edge: int, repeats: int) -> None:
    """Passes sur un tensor figé (1, 3, edge, edge) : la forme que le graphe va capturer."""
    import torch

    sample = torch.zeros(1, 3, edge, edge, device=device, dtype=dtype)
    with torch.inference_mode():
        for _ in range(max(1, repeats)):
            callable_(sample)


def compile_vision(model, device: str, dtype, edge: int, warmups: int = 3) -> str:
    """``torch.compile`` de l'encodeur vision seul + warm-up ; repli silencieux si ça échoue."""
    if device != "cuda":
        return "skipped:no-cuda"
    import torch

    original = model.vision_encoder
    try:
        model.vision_encoder = torch.compile(original)
        _warmup(model.vision_encoder, device, dtype, edge, warmups)
        model._vision_original = original
        return "ok"
    except Exception as exc:  # noqa: BLE001 — la compilation est un bonus, jamais un blocage
        model.vision_encoder = original
        return f"failed:{type(exc).__name__}:{str(exc)[:120]}"


def graph_vision(model, device: str, dtype, edge: int, warmups: int = 3) -> str:
    """Capture CUDA graph de l'encodeur vision (forme statique) ; repli silencieux.

    Les tenseurs sortis du graphe sont réécrits à chaque ``replay`` : sûr ici parce que le
    conteneur sérialise tout derrière un verrou GPU et consomme la sortie dans la foulée.
    Activé uniquement par ``FAST_CUDA_GRAPH=1`` — non testé sans GPU, donc laissé éteint.
    """
    import torch

    if device != "cuda" or not hasattr(torch.cuda, "CUDAGraph"):
        return "skipped:no-cuda"
    module = model.vision_encoder
    try:
        static_in, static_out, graph = _capture_vision_graph(module, torch, device,
                                                             dtype, edge, warmups)

        class _GraphReplay(torch.nn.Module):
            """Encodeur vision rejoué par graphe CUDA, à forme d'entrée fixe.

            Un ``nn.Module`` et pas une fonction : ``vision_encoder`` est un
            sous-module enregistré, PyTorch refuse d'y assigner une closure
            (``cannot assign ... as child module``). La forme est stable parce que
            ``runtime_edge`` est faux ; si une autre forme arrive, on repasse par le
            module d'origine au lieu de corrompre le graphe. Nichée ici : torch
            n'est importable qu'à l'intérieur (pas de stack GPU aux tests).
            """

            def __init__(self) -> None:
                super().__init__()
                self.static_in = static_in
                self.static_out = static_out
                self.shape = tuple(static_in.shape)
                self.original = module
                self._graph = graph

            def forward(self, pixel_values):
                if tuple(pixel_values.shape) != self.shape:
                    return self.original(pixel_values)
                self.static_in.copy_(pixel_values)
                self._graph.replay()
                return self.static_out

        model.vision_encoder = _GraphReplay()
        return "ok"
    except Exception as exc:  # noqa: BLE001
        model.vision_encoder = module
        return f"failed:{type(exc).__name__}:{str(exc)[:120]}"


def _capture_vision_graph(module, torch, device: str, dtype, edge: int, warmups: int = 3):
    """Capture le graphe CUDA de l'encodeur (warm-up + enregistrement) ; lève sinon."""
    static_in = torch.zeros(1, 3, edge, edge, device=device, dtype=dtype)
    side = torch.cuda.Stream()
    side.wait_stream(torch.cuda.current_stream())
    with torch.cuda.stream(side):
        _warmup(module, device, dtype, edge, warmups)
    torch.cuda.current_stream().wait_stream(side)
    graph = torch.cuda.CUDAGraph()
    with torch.inference_mode(), torch.cuda.graph(graph):
        static_out = module(static_in)
    return static_in, static_out, graph
