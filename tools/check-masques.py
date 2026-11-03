#!/usr/bin/env python3
"""Garde des 12 masques de référence (ordre user 03/11).

Vérifie, sans compilation ni réseau (stdlib, secondes) :
  1. les 12 concepts EN sont dans `LivePrompts.default` (prompts dehors) ;
  2. `default` tient sous le plafond `LivePrompts.MAX` ;
  3. chaque libellé FR donne son concept EN via `model_concept`, dans les
     DEUX exemplaires serveur (`backend/` et `live_fast/`).

Référence (FR → EN demandé dehors) : escaliers→stairs, voiture→car,
humain→person (*), poteau→pole, trottoirs→sidewalk,
feu piéton→pedestrian traffic light, camionnette→van, bus→bus,
murs→wall, poussette→stroller, chien→dog, enfant→child.
(*) « humain » est détecté par le prompt « person » ; la saisie FR
« humain » (Follow, Find my X) donne « human » côté serveur — les deux
formes sont exigées, chacune à sa place.

Usage : python3 tools/check-masques.py [--root DIR]
Sortie 0 = conforme, 1 = régression (détail sur stdout).
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import sys
from pathlib import Path

LIVE_PROMPTS = Path("core/src/main/java/dev/sam3vision/core/live/LivePrompts.kt")
ALIASES_BACKEND = Path("server/backend/concepts.py")
ALIASES_FAST = Path("server/backend/live_fast/concepts.py")

# (libellé FR, concept EN demandé dehors). « humain » → « person ».
REFERENCE = (
    ("escaliers", "stairs"),
    ("voiture", "car"),
    ("humain", "person"),
    ("poteau", "pole"),
    ("trottoirs", "sidewalk"),
    ("feu piéton", "pedestrian traffic light"),
    ("camionnette", "van"),
    ("bus", "bus"),
    ("murs", "wall"),
    ("poussette", "stroller"),
    ("chien", "dog"),
    ("enfant", "child"),
)

# Traduction FR→EN attendue côté serveur (saisie FR, Follow, Find my X).
SERVER_FR_EN = dict(REFERENCE) | {"humain": "human"}


def _read(root: Path, rel: Path) -> str:
    path = root / rel
    if not path.is_file():
        raise FileNotFoundError(str(rel))
    return path.read_text(encoding="utf-8")


def parse_default(text: str) -> list[str]:
    """Concepts de `val default = listOf(...)` (chaînes entre guillemets)."""
    match = re.search(r"val\s+default\b[^=]*=\s*listOf\((.*?)\)", text, re.DOTALL)
    if not match:
        raise ValueError("LivePrompts.default introuvable")
    return re.findall(r'"([^"]+)"', match.group(1))


def parse_max(text: str) -> int:
    """Plafond `const val MAX = N`."""
    match = re.search(r"const\s+val\s+MAX\s*=\s*(\d+)", text)
    if not match:
        raise ValueError("LivePrompts.MAX introuvable")
    return int(match.group(1))


def load_aliases(path: Path):
    """Module concepts.py importé depuis son fichier (sans le paquet)."""
    name = "masques_probe_" + path.parent.name
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_tree(root: Path) -> list[str]:
    """Erreurs de conformité (vide = conforme)."""
    errors = []
    try:
        live = _read(root, LIVE_PROMPTS)
    except (FileNotFoundError, ValueError) as exc:
        return [f"LivePrompts.kt illisible : {exc}"]
    try:
        default, plafond = parse_default(live), parse_max(live)
    except ValueError as exc:
        return [str(exc)]
    for _fr, en in REFERENCE:
        if en not in default:
            errors.append(f"LivePrompts.default sans {en!r}")
    if len(default) > plafond:
        errors.append(f"LivePrompts.default à {len(default)} > plafond {plafond}")
    for rel in (ALIASES_BACKEND, ALIASES_FAST):
        try:
            module = load_aliases(root / rel)
        except (FileNotFoundError, ImportError, SyntaxError) as exc:
            errors.append(f"{rel} illisible : {exc}")
            continue
        for fr, en in sorted(SERVER_FR_EN.items()):
            if module.model_concept(fr) != en:
                errors.append(f"{rel} : {fr!r} ne donne pas {en!r}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Garde des 12 masques de référence.")
    parser.add_argument("--root", default=".", help="racine du dépôt (défaut : .)")
    args = parser.parse_args(argv)
    errors = check_tree(Path(args.root))
    if errors:
        print(f"MASQUES-REFERENCE : {len(errors)} régression(s)")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"MASQUES-REFERENCE : 12/12 dehors, plafond tenu, alias FR→EN OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
