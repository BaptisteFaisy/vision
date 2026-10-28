#!/usr/bin/env python3
"""Garde du design Portrait (obligatoire, docs/ISEE-DESIGN-PORTRAIT.md).

Quatre contrôles, sans compilation :

1. Quarantaine : les symboles de l'ancien design (satellites, grille de
   pixels, looks de bulles, pagination) ne sont utilisables que par les
   fichiers legacy et les tests — jamais par le chemin de production.
2. Câblage : le pane iSee assemble bien la scène Portrait, et le contrat
   `IseePortrait` expose bien palettes, géométrie et fonctions (les valeurs
   sont figées par `IseePortraitTest`). Les commentaires ne comptent pas :
   un câblage commenté est un câblage absent.
3. Boule gelée : le code qui dessine la boule (`IseePortraitOrb.kt` entier
   et 5 unités d'animation de `IseeScenePane.kt`) correspond à l'octet près
   à `tools/orb-frozen-baseline.json`. Changement voulu (demande explicite
   du user uniquement) : `python3 tools/freeze-orb-baseline.py`, puis
   relire le diff — docs/ISEE-DESIGN-PORTRAIT.md §6.
4. Horloge : le pane n'utilise jamais `rememberInfiniteTransition`
   (`animator_duration_scale=0` la fige — Redmi, 2026-10-28) ; le temps de
   scène vient d'une boucle coroutine sur horloge monotone.

Usage : python3 tools/check-isee-design.py [racine]
Sortie : 0 si conforme, 1 sinon (2 si la racine n'existe pas).
"""
import argparse
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import guard_lib

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
    # Ligne de statut + pill : décâblée une fois par la refonte Portrait
    # (580b2e5), tous les échecs devenaient invisibles (écran figé READY).
    "SceneBottomBar",
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

# Boule gelée : le fichier boule entier (shader, repli, peintre) et les
# unités d'animation du pane (horloge, couche, mapping, ombre, couronne).
# L'assemblage autour (caméra, en-tête, phrase) reste libre d'évoluer.
ORB_FILE = "app/src/main/java/dev/sam3vision/app/isee/IseePortraitOrb.kt"
ORB_PANE = "app/src/main/java/dev/sam3vision/app/isee/IseeScenePane.kt"
ORB_REGIONS = (
    "rememberSceneTime",
    "OrbLayer",
    "orbLookOf",
    "drawOrbShadow",
    "drawVoiceCrown",
)
ORB_BASELINE = "tools/orb-frozen-baseline.json"

# Horloge de la scène : la transition infinie est figée par
# `animator_duration_scale=0` (Redmi, 2026-10-28 : boule pixel-identique à
# 4 s d'intervalle) — le pane utilise `sceneTimeSeconds` sur horloge monotone.
CLOCK_BANNED = (
    "rememberInfiniteTransition",
    "infiniteRepeatable",
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


def check_wiring(root, limits):
    """Le pane assemble la scène Portrait, le contrat expose ses symboles.

    Commentaires et chaînes neutralisés (garde de limites) : un symbole
    seulement cité en commentaire ne câble rien.
    """
    errors = []
    for rel, needs in ((PANE, PANE_NEEDS), (PORTRAIT, PORTRAIT_NEEDS)):
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            errors.append("%s — fichier Portrait manquant" % rel)
            continue
        code = limits.strip_strings_comments(_read(path))
        for symbol in needs:
            if symbol not in code:
                errors.append(
                    "%s — symbole Portrait `%s` manquant" % (rel, symbol))
    return errors


def orb_hashes(root, limits):
    """Empreintes du code gelé : fichier boule + 5 unités d'animation."""
    hashes = {"version": 1, "files": {}, "regions": {}}
    with open(os.path.join(root, ORB_FILE), "rb") as handle:
        hashes["files"][ORB_FILE] = hashlib.sha256(handle.read()).hexdigest()
    text = _read(os.path.join(root, ORB_PANE))
    _, found = limits.kotlin_declarations(text)
    for name in ORB_REGIONS:
        spans = [(start, end) for found_name, start, end in found
                 if found_name == name]
        if len(spans) != 1:
            raise LookupError("%s : %d occurrence(s) de `%s` (attendu 1)"
                              % (ORB_PANE, len(spans), name))
        start, end = spans[0]
        region = text[start:end + 1].rstrip() + "\n"
        key = "%s::%s" % (ORB_PANE, name)
        hashes["regions"][key] = hashlib.sha256(
            region.encode("utf-8")).hexdigest()
    return hashes


def check_frozen_orb(root, limits):
    """Le code gelé de la boule correspond à la baseline, à l'octet près."""
    base_path = os.path.join(root, ORB_BASELINE)
    if not os.path.isfile(base_path):
        return ["%s — baseline absente, la régénérer : "
                "python3 tools/freeze-orb-baseline.py" % ORB_BASELINE]
    try:
        with open(base_path, encoding="utf-8") as handle:
            baseline = json.load(handle)
    except ValueError:
        return ["%s — baseline illisible, la régénérer : "
                "python3 tools/freeze-orb-baseline.py" % ORB_BASELINE]
    try:
        current = orb_hashes(root, limits)
    except (OSError, LookupError) as exc:
        return ["boule Portrait gelée — %s" % exc]
    errors = []
    for section in ("files", "regions"):
        want = baseline.get(section, {})
        for key, sha in sorted(current[section].items()):
            if want.get(key) != sha:
                errors.append(
                    "boule Portrait modifiée — `%s` ne correspond plus à %s "
                    "(changement voulu et demandé par le user ? "
                    "python3 tools/freeze-orb-baseline.py puis relire le diff ; "
                    "sinon, annuler la modification) — "
                    "docs/ISEE-DESIGN-PORTRAIT.md §6" % (key, ORB_BASELINE))
    return errors


def check_scene_clock(root, limits):
    """Pas de transition infinie dans le pane : figée par les échelles anim à 0.

    Commentaires et chaînes neutralisés : la citer en commentaire (pour dire
    pourquoi elle est bannie) ne déclenche pas le garde.
    """
    path = os.path.join(root, PANE)
    if not os.path.isfile(path):
        return []
    code = limits.strip_strings_comments(_read(path))
    return ["%s — `%s` interdit (figé par animator_duration_scale=0 ; "
            "horloge : `sceneTimeSeconds`)" % (PANE, symbol)
            for symbol in CLOCK_BANNED if symbol in code]


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Garde : design Portrait obligatoire, ancien design en quarantaine, boule gelée.")
    parser.add_argument("root", nargs="?", default=".")
    args = parser.parse_args(argv)
    if not os.path.isdir(args.root):
        print("racine introuvable : %s" % args.root, file=sys.stderr)
        return 2
    try:
        limits = guard_lib.load_checker(args.root)
    except FileNotFoundError as exc:
        print("check-isee-design : %s" % exc, file=sys.stderr)
        return 1
    errors = (check_quarantine(args.root) + check_wiring(args.root, limits)
              + check_frozen_orb(args.root, limits)
              + check_scene_clock(args.root, limits))
    if not errors:
        print("check-isee-design : OK — Portrait câblé, boule gelée, horloge monotone, ancien design en quarantaine.")
        return 0
    print("check-isee-design : %d erreur(s) :" % len(errors))
    for error in errors:
        print("  " + error)
    return 1


if __name__ == "__main__":
    sys.exit(main())
