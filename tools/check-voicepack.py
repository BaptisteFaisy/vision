#!/usr/bin/env python3
"""Garde du pack voix : le catalogue colle au code et aux fichiers.

Vérifie, sans clé ni réseau (runnable en CI) :
1. chaque texte du catalogue existe dans le Kotlin (littéral exact, ou
   morceaux `parts` pour les phrases composées) ;
2. chaque MP3 attendu existe et n'est pas vide (sauf mention --allow-missing,
   pour les branches qui codent le pack avant sa génération) ;
3. aucun MP3 `vp_*` en surplus sur le disque (clip supprimé des catalogues
   mais fichier oublié — les 99 FR morts du 01/11/2026) ;
4. assets/vp_voice.txt désigne la voix par défaut de l'app (LAURE_VOICE_ID).

Racine et voix par défaut : une seule implémentation, celle de
`tools/gen-voicepack.py` (chargée par `guard_lib`, tirets oblige).

Usage : python3 tools/check-voicepack.py [--allow-missing]
"""
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import guard_lib

PHONE_SOURCES = ("app/src/main/java", "core/src/main/java")
LANGS = ("fr", "en")


def _gen():
    here = os.path.dirname(os.path.abspath(__file__))
    return guard_lib.load_py_module(
        "gen_voicepack", os.path.join(here, "gen-voicepack.py"))


def isee_expected(root: str) -> set:
    """Noms vp_* du catalogue clips EN (même res/raw : un seul pack)."""
    try:
        with open(os.path.join(root, "tools/isee-clips-en-catalog.json"),
                  encoding="utf-8") as fh:
            isee = json.load(fh)["clips"]
    except (OSError, ValueError, KeyError):
        return set()
    return {f"vp_{clip['id']}_en.mp3" for clip in isee}


def failures(root: str, allow_missing: bool) -> list:
    problems = []
    gen = _gen()
    with open(os.path.join(root, "tools/voicepack-catalog.json"),
              encoding="utf-8") as fh:
        catalog = json.load(fh)["clips"]
    laure = gen.default_voice(root, strict=False)
    expected = set()
    for clip in catalog:
        cid = clip["id"]
        if clip.get("parts"):
            needles = list(clip["parts"])  # composée en code : morceaux only
        else:
            needles = [clip[lang] for lang in LANGS if clip.get(lang)]
        for needle in needles:
            found = subprocess.run(
                ["grep", "-r", "--fixed-strings", "--quiet", needle]
                + list(PHONE_SOURCES),
                cwd=root).returncode == 0
            if not found:
                problems.append(f"{cid}: « {needle} » introuvable dans le Kotlin")
        for lang in LANGS:
            if not clip.get(lang):
                continue
            name = f"vp_{cid}_{lang}.mp3"
            expected.add(name)
            mp3 = os.path.join(root, "app/src/main/res/raw", name)
            if not (os.path.isfile(mp3) and os.path.getsize(mp3)):
                if not allow_missing:
                    problems.append(f"{cid}: {name} manquant/vide")
    expected |= isee_expected(root)
    raw_dir = os.path.join(root, "app/src/main/res/raw")
    if os.path.isdir(raw_dir):
        for name in sorted(os.listdir(raw_dir)):
            if name.startswith("vp_") and name not in expected:
                problems.append(f"{name} en surplus (hors catalogue : supprimer)")
    stamp = os.path.join(root, "app/src/main/assets/vp_voice.txt")
    if os.path.isfile(stamp):
        with open(stamp, encoding="utf-8") as fh:
            if fh.read().strip() != laure:
                problems.append("vp_voice.txt ≠ LAURE_VOICE_ID "
                                "(pack d'une autre voix : régénérer)")
    elif not allow_missing:
        problems.append("assets/vp_voice.txt manquant")
    return problems


def main(argv: list) -> int:
    allow_missing = "--allow-missing" in argv
    unknown = [a for a in argv if a != "--allow-missing"]
    if unknown:
        print(f"check-voicepack: option inconnue {unknown[0]}", file=sys.stderr)
        return 2
    problems = failures(_gen().repo_root(), allow_missing)
    for problem in problems:
        print(f"check-voicepack: {problem}", file=sys.stderr)
    if problems:
        print(f"check-voicepack: {len(problems)} problème(s)", file=sys.stderr)
        return 1
    print("check-voicepack: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
