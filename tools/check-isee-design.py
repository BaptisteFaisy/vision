#!/usr/bin/env python3
"""Garde du design Portrait (obligatoire, docs/ISEE-DESIGN-PORTRAIT.md).

Deux contrôles, sans compilation :

1. Quarantaine : les symboles de l'ancien design (satellites, grille de
   pixels, looks de bulles, pagination) ne sont utilisables que par les
   fichiers legacy et les tests — jamais par le chemin de production.
2. Câblage : le pane iSee assemble bien la scène Portrait, et le contrat
   `IseePortrait` expose bien palettes, géométrie et fonctions (les valeurs
   sont figées par `IseePortraitTest`).

Usage : python3 tools/check-isee-design.py [racine]
Sortie : 0 si conforme, 1 sinon (2 si la racine n'existe pas).
"""
import argparse
import os
import re
import sys

# Symboles legacy : définis dans core/.../iseedesign/IseeScene.kt (sauf les
# deux classes d'art), utilisables par les fichiers LEGACY_OK et les tests.
LEGACY_SYMBOLS = (
    "satellitePositions",
    "sampleGrid",
    "pixelTrip",
    "bubbleLookIndex",
    "BubbleLook",
    "textPages",
    "IseeSceneArt",
    "IseeScenePixels",
)
LEGACY_RE = re.compile(r"\b(%s)\b" % "|".join(LEGACY_SYMBOLS))

LEGACY_OK = (
    "core/src/main/java/dev/sam3vision/core/iseedesign/IseeScene.kt",
    "app/src/main/java/dev/sam3vision/app/isee/IseeSceneArt.kt",
    "app/src/main/java/dev/sam3vision/app/isee/IseeScenePixels.kt",
    "app/src/main/java/dev/sam3vision/app/isee/preview/",
)

PANE = "app/src/main/java/dev/sam3vision/app/isee/IseeScenePane.kt"
PANE_NEEDS = (
    "portraitGeometry",
    "rememberOrbPainter",
    "SpokenLine",
    "PortraitCamera",
    "IseePortrait",
)

PORTRAIT = "core/src/main/java/dev/sam3vision/core/iseedesign/IseePortrait.kt"
PORTRAIT_NEEDS = (
    "YOU",
    "YOU_INK",
    "SEE",
    "SEE_INK",
    "INK",
    "SUB",
    "DIM",
    "IDLE",
    "PAL_YOU",
    "PAL_SEE",
    "REST_Y",
    "REST_R",
    "PICTURE_H",
    "CAM_Y",
    "CAM_R",
    "FADE",
    "portraitGeometry",
    "pictureAlpha",
    "focusPull",
    "wordsSpoken",
)

SKIP_DIRS = {".git", ".gradle", "build", ".idea", "tmp", "node_modules",
             "__pycache__", ".venv", "venv"}


def _read(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def _legacy_allowed(relpath):
    return relpath in LEGACY_OK or any(
        relpath.startswith(prefix) for prefix in LEGACY_OK
        if prefix.endswith("/"))


def check_quarantine(root):
    """Références legacy hors fichiers legacy et tests : une erreur chacune."""
    errors = []
    for module in ("app/src/main", "core/src/main"):
        base = os.path.join(root, module)
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
            for name in sorted(filenames):
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, name)
                rel = os.path.relpath(path, root).replace(os.sep, "/")
                if _legacy_allowed(rel):
                    continue
                for lineno, line in enumerate(_read(path).splitlines(), 1):
                    for match in LEGACY_RE.finditer(line):
                        errors.append(
                            "%s:%d — symbole legacy `%s` hors quarantaine "
                            "(ancien design interdit, voir "
                            "docs/ISEE-DESIGN-PORTRAIT.md §4)"
                            % (rel, lineno, match.group(1)))
    return errors


def check_wiring(root):
    """Le pane assemble la scène Portrait, le contrat expose ses symboles."""
    errors = []
    for rel, needs in ((PANE, PANE_NEEDS), (PORTRAIT, PORTRAIT_NEEDS)):
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            errors.append("%s — fichier Portrait manquant" % rel)
            continue
        text = _read(path)
        for symbol in needs:
            if symbol not in text:
                errors.append(
                    "%s — symbole Portrait `%s` manquant" % (rel, symbol))
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Garde : design Portrait obligatoire, ancien design en quarantaine.")
    parser.add_argument("root", nargs="?", default=".")
    args = parser.parse_args(argv)
    if not os.path.isdir(args.root):
        print("racine introuvable : %s" % args.root, file=sys.stderr)
        return 2
    errors = check_quarantine(args.root) + check_wiring(args.root)
    if not errors:
        print("check-isee-design : OK — Portrait câblé, ancien design en quarantaine.")
        return 0
    print("check-isee-design : %d erreur(s) :" % len(errors))
    for error in errors:
        print("  " + error)
    return 1


if __name__ == "__main__":
    sys.exit(main())
