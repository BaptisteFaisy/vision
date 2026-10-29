#!/usr/bin/env python3
"""Garde du pack voix : le catalogue colle au code et aux fichiers.

Vérifie, sans clé ni réseau (runnable en CI) :
1. chaque texte du catalogue existe dans le Kotlin (littéral exact, ou
   morceaux `parts` pour les phrases composées) ;
2. chaque MP3 attendu existe et n'est pas vide (sauf mention --allow-missing,
   pour les branches qui codent le pack avant sa génération) ;
3. assets/vp_voice.txt désigne la voix par défaut de l'app (LAURE_VOICE_ID).

Usage : python3 tools/check-voicepack.py [--allow-missing]
"""
import json
import os
import re
import subprocess
import sys

PHONE_SOURCES = ("app/src/main/java", "core/src/main/java")


def repo_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def failures(root: str, allow_missing: bool) -> list:
    problems = []
    with open(os.path.join(root, "tools/voicepack-catalog.json"),
              encoding="utf-8") as fh:
        catalog = json.load(fh)["clips"]
    with open(os.path.join(root, "core/src/main/java/dev/sam3vision"
                           "/core/settings/Settings.kt"),
              encoding="utf-8") as fh:
        settings = fh.read()
    laure = re.search(r'LAURE_VOICE_ID\s*=\s*"([^"]+)"', settings)
    laure = laure.group(1) if laure else ""
    for clip in catalog:
        cid = clip["id"]
        if clip.get("parts"):
            needles = list(clip["parts"])  # composée en code : morceaux only
        else:
            needles = [clip[lang] for lang in ("fr", "en") if clip.get(lang)]
        for needle in needles:
            found = subprocess.run(
                ["grep", "-r", "--fixed-strings", "--quiet", needle]
                + list(PHONE_SOURCES),
                cwd=root).returncode == 0
            if not found:
                problems.append(f"{cid}: « {needle} » introuvable dans le Kotlin")
        for lang in ("fr", "en"):
            if not clip.get(lang):
                continue
            mp3 = os.path.join(root, "app/src/main/res/raw",
                               f"vp_{cid}_{lang}.mp3")
            if not (os.path.isfile(mp3) and os.path.getsize(mp3)):
                if not allow_missing:
                    problems.append(f"{cid}: {os.path.basename(mp3)} manquant/vide")
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
    problems = failures(repo_root(), allow_missing)
    for problem in problems:
        print(f"check-voicepack: {problem}", file=sys.stderr)
    if problems:
        print(f"check-voicepack: {len(problems)} problème(s)", file=sys.stderr)
        return 1
    print("check-voicepack: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
