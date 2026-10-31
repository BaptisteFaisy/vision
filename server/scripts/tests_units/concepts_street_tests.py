"""Tests des alias FR→EN de bordures (30/10) : trottoir abaissé et caniveau.

Vérifie les nouveaux concepts rue (bordure, rebord, caniveau, bateau) dans les
deux exemplaires (racine + live_fast), sans collision après désaccentuation.
Chargé par ``scripts/test_units.py`` comme les autres groupes (voir weights_tests).
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[2] / "backend"
LIVE_FAST_DIR = BACKEND_DIR / "live_fast"


def _fresh_live_fast_concepts():
    """Exemplaire déployé (live_fast/concepts.py) importé à neuf."""
    spec = importlib.util.spec_from_file_location(
        "live_fast_concepts_street_probe", LIVE_FAST_DIR / "concepts.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_concepts_street(concepts) -> None:
    """Bordures, caniveaux et bateaux : FR→EN + parité déployée."""
    from test_units import check

    # Sens rue exigés (30/10).
    check("rue : bordure -> curb",
          concepts.model_concept("bordure") == "curb")
    check("rue : bordures -> curb",
          concepts.model_concept("bordures") == "curb")
    check("rue : rebord -> curb",
          concepts.model_concept("rebord") == "curb")
    # Formes longues et synonymes voirie (30/10, §17) : une passe modèle par objet.
    for fr, en in [("bordure de trottoir", "curb"), ("bordure du trottoir", "curb"),
                   ("rebord de trottoir", "curb"), ("rebord du trottoir", "curb"),
                   ("bord de trottoir", "curb"), ("bord du trottoir", "curb"),
                   ("kerb", "curb"), ("marche d'escalier", "step"),
                   ("marches d'escalier", "steps"), ("rigole", "gutter"),
                   ("passage bateau", "curb ramp")]:
        check(f"rue : {fr} -> {en}", concepts.model_concept(fr) == en)
    check("rue : caniveau -> gutter",
          concepts.model_concept("caniveau") == "gutter")
    check("rue : bateau -> curb ramp (bateau de trottoir)",
          concepts.model_concept("bateau") == "curb ramp")
    check("rue : bateau de trottoir -> curb ramp",
          concepts.model_concept("bateau de trottoir") == "curb ramp")
    # Sens « boat » préservé via la forme explicite (homonyme levé).
    check("rue : bateau à voile -> boat (sens préservé)",
          concepts.model_concept("bateau à voile") == "boat")
    # Robustesse casse/articles (même plomberie que les autres alias).
    check("rue : casse et articles (Bordure, une bordure, CANIVEAU)",
          concepts.model_concept("Bordure") == "curb"
          and concepts.model_concept("une bordure") == "curb"
          and concepts.model_concept("CANIVEAU") == "gutter"
          and concepts.model_concept("le rebord") == "curb")
    # Pas de collision après désaccentuation (même règle que test_concepts_sync).
    check("rue : aucune collision après désaccentuation",
          len(concepts._ALIASES_NORM) == len(concepts.CONCEPT_ALIASES),
          "deux clés perdent leur distinction sans accents")
    # Parité déployée : live_fast == racine (même règle que VENDORED_PARITY).
    deployed = _fresh_live_fast_concepts()
    check("rue : table déployée == racine",
          deployed.CONCEPT_ALIASES == concepts.CONCEPT_ALIASES,
          "divergence live_fast/concepts.py")
    check("rue : déployé traduit pareil (bordure, bateau)",
          deployed.model_concept("bordure") == "curb"
          and deployed.model_concept("bateau") == "curb ramp")
