"""Tests des alias FR→EN des feux piétons (31/10, ordre user : piétons seuls).

Vérifie les nouveaux concepts feu piéton dans les deux exemplaires
(racine + live_fast), sans collision après désaccentuation.
Chargé par ``scripts/test_units.py`` comme les autres groupes.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[2] / "backend"
LIVE_FAST_DIR = BACKEND_DIR / "live_fast"


def _fresh_live_fast_concepts():
    """Exemplaire déployé (live_fast/concepts.py) importé à neuf."""
    spec = importlib.util.spec_from_file_location(
        "live_fast_concepts_crossing_probe", LIVE_FAST_DIR / "concepts.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_concepts_crossing(concepts) -> None:
    """Feux piétons : FR→EN + parité déployée."""
    from test_units import check

    # Sens piéton exigé (31/10) : la couleur se lit en local, jamais par un
    # prompt d'état (rappel 12,5 %, docs/MESURES-SAM3-FEUX.md §2) — les
    # formes rouge/vert pointent le même concept générique piéton.
    for fr in ["feu piéton", "feux piétons", "feu piéton rouge",
               "feu piéton vert", "signal piéton", "signaux piétons"]:
        check(f"traversée : {fr} -> pedestrian traffic light",
              concepts.model_concept(fr) == "pedestrian traffic light")
    # Sens voiture préservé (suivi manuel possible, jamais pour traverser).
    check("traversée : feu tricolore -> traffic light (sens préservé)",
          concepts.model_concept("feu tricolore") == "traffic light")
    # Robustesse casse/articles/accents (même plomberie que les alias rue).
    check("traversée : casse, articles et accents (Feu Piéton, un feu pieton)",
          concepts.model_concept("Feu Piéton") == "pedestrian traffic light"
          and concepts.model_concept("un feu pieton") == "pedestrian traffic light"
          and concepts.model_concept("les signaux pietons")
          == "pedestrian traffic light")
    # Pas de collision après désaccentuation (même règle que test_concepts_sync).
    check("traversée : aucune collision après désaccentuation",
          len(concepts._ALIASES_NORM) == len(concepts.CONCEPT_ALIASES),
          "deux clés perdent leur distinction sans accents")
    # Parité déployée : live_fast == racine (même règle que VENDORED_PARITY).
    deployed = _fresh_live_fast_concepts()
    check("traversée : table déployée == racine",
          deployed.CONCEPT_ALIASES == concepts.CONCEPT_ALIASES,
          "divergence live_fast/concepts.py")
    check("traversée : déployé traduit pareil (feu piéton, feu tricolore)",
          deployed.model_concept("feu piéton") == "pedestrian traffic light"
          and deployed.model_concept("feu tricolore") == "traffic light")
