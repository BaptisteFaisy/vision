#!/usr/bin/env python3
"""Garde des clips anglais SPEC v4 : le catalogue colle au code et aux fichiers.

Vérifie, sans clé ni réseau (runnable en CI) :
1. chaque texte EN du catalogue (nouveaux + reuse) existe en littéral exact
   dans le miroir Kotlin (IseeClipsEn.kt + IseeClipsSidewalk.kt) ;
2. chaque reuse existe dans tools/voicepack-catalog.json avec le même texte EN
   (pas de doublon audio qui diverge) ;
3. aucun id nouveau ne collisionne avec le pack existant ;
4. chaque MP3 attendu existe et n'est pas vide (sauf --allow-missing, pour les
   branches qui codent les clips avant leur génération) ;
5. assets/vp_voice.txt désigne la voix par défaut de l'app (LAURE_VOICE_ID) ;
6. les clips à durée contrainte ([DURATIONS]) tiennent leur budget
   (durée MP3 lue en stdlib, sans ffprobe).

Usage : python3 tools/check-isee-clips-en.py [--allow-missing]
"""
import json
import os
import re
import sys

MIRRORS = [
    "core/src/main/java/dev/sam3vision/core/speech/IseeClipsEn.kt",
    "core/src/main/java/dev/sam3vision/core/speech/IseeClipsSidewalk.kt",
]
CATALOG = "tools/isee-clips-en-catalog.json"
LEGACY = "tools/voicepack-catalog.json"
ID_RE = re.compile(r"^[a-z0-9_]+$")

# Budget de durée par clip (secondes) : « Crosswalk. » dit l'annonce du
# passage devant toutes les 2 s — ordre user du 02/11, moins de 500 ms.
DURATIONS = {"crosswalk": 0.5}


def repo_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_json(root: str, rel: str) -> dict:
    with open(os.path.join(root, rel), encoding="utf-8") as fh:
        return json.load(fh)


def check_ids(clips: list, legacy_ids: set, problems: list) -> None:
    """Ids uniques, au format res/raw, sans collision avec le pack existant."""
    seen = set()
    for clip in clips:
        cid = clip.get("id", "")
        if not cid or not ID_RE.match(cid):
            problems.append(f"{cid or '?'}: id hors format res/raw ([a-z0-9_]+)")
        if cid in seen:
            problems.append(f"{cid}: id en double dans {CATALOG}")
        seen.add(cid)
        if cid in legacy_ids:
            problems.append(f"{cid}: id déjà pris par {LEGACY} "
                            "(mettre en reuse, pas en doublon)")
        if not clip.get("en", "").strip():
            problems.append(f"{cid}: texte EN vide")


def check_mirror(entries: list, mirror_src: str, problems: list) -> None:
    """Chaque texte EN est un littéral exact du miroir Kotlin."""
    for entry in entries:
        text = entry.get("en", "")
        if text and text not in mirror_src:
            problems.append(f"{entry.get('id')}: « {text} » "
                            "introuvable dans le miroir Kotlin")


def check_reuse(reuse: list, legacy: dict, problems: list) -> None:
    """Chaque reuse existe dans le pack existant, même texte EN."""
    for entry in reuse:
        cid = entry.get("id", "")
        expected = entry.get("en", "")
        actual = legacy.get(cid)
        if actual is None:
            problems.append(f"{cid}: reuse absent de {LEGACY}")
        elif actual != expected:
            problems.append(f"{cid}: reuse « {expected} » ≠ pack "
                            f"« {actual} » (divergence audio)")


def check_mp3(ids: list, root: str, allow_missing: bool, problems: list) -> None:
    """Chaque MP3 attendu existe et n'est pas vide (sauf --allow-missing)."""
    for cid in ids:
        mp3 = os.path.join(root, "app/src/main/res/raw", f"vp_{cid}_en.mp3")
        if not (os.path.isfile(mp3) and os.path.getsize(mp3)):
            if not allow_missing:
                problems.append(f"{cid}: vp_{cid}_en.mp3 manquant/vide")


def mp3_duration(path: str) -> float | None:
    """Durée d'un MP3 en secondes (stdlib, sans ffprobe) : saute le tag ID3v2
    puis compte les trames MPEG-1 Layer III (1152 échantillons chacune).
    `None` si illisible (jamais deviné)."""
    try:
        with open(path, "rb") as fh:
            data = fh.read()
    except OSError:
        return None
    pos = 0
    if data[:3] == b"ID3":  # tag ID3v2 : taille synchsafe sur 4 octets
        size = 0
        for byte in data[6:10]:
            size = (size << 7) | byte
        pos = 10 + size
    rates = (44100, 48000, 32000)
    bitrates = (0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    frames = 0
    rate = 0
    while pos + 4 <= len(data):
        if data[pos] != 0xFF or data[pos + 1] & 0xE0 != 0xE0:
            pos += 1
            continue
        version = (data[pos + 1] >> 3) & 0x03
        layer = (data[pos + 1] >> 1) & 0x03
        bitrate = bitrates[(data[pos + 2] >> 4) & 0x0F]
        sample = (data[pos + 2] >> 2) & 0x03
        padding = (data[pos + 2] >> 1) & 0x01
        if version != 0x03 or layer != 0x01 or not bitrate or sample == 0x03:
            pos += 1
            continue
        rate = rates[sample]
        pos += (144000 * bitrate) // rate + padding
        frames += 1
    if not frames or not rate:
        return None
    return frames * 1152 / rate


def check_durations(root: str, problems: list) -> None:
    """Les clips de [DURATIONS] tiennent leur budget (absent = check_mp3)."""
    for cid, budget in DURATIONS.items():
        mp3 = os.path.join(root, "app/src/main/res/raw", f"vp_{cid}_en.mp3")
        if not (os.path.isfile(mp3) and os.path.getsize(mp3)):
            continue
        seconds = mp3_duration(mp3)
        if seconds is None:
            problems.append(f"{cid}: vp_{cid}_en.mp3 illisible")
        elif seconds >= budget:
            problems.append(f"{cid}: vp_{cid}_en.mp3 dure {seconds:.2f} s "
                            f"(budget {budget:.1f} s : régénérer puis rogner, "
                            "voir docs/VOICEPACK.md)")


def check_voice(root: str, allow_missing: bool, problems: list) -> None:
    """vp_voice.txt désigne la voix par défaut (LAURE_VOICE_ID)."""
    settings = os.path.join(
        root, "core/src/main/java/dev/sam3vision/core/settings/Settings.kt")
    with open(settings, encoding="utf-8") as fh:
        mat = re.search(r'LAURE_VOICE_ID\s*=\s*"([^"]+)"', fh.read())
    laure = mat.group(1) if mat else ""
    stamp = os.path.join(root, "app/src/main/assets/vp_voice.txt")
    if os.path.isfile(stamp):
        with open(stamp, encoding="utf-8") as fh:
            if fh.read().strip() != laure:
                problems.append("vp_voice.txt ≠ LAURE_VOICE_ID "
                                "(pack d'une autre voix : régénérer)")
    elif not allow_missing:
        problems.append("assets/vp_voice.txt manquant")


def failures(root: str, allow_missing: bool) -> list:
    problems = []
    catalog = load_json(root, CATALOG)
    clips = catalog.get("clips", [])
    reuse = catalog.get("reuse", [])
    legacy = {c["id"]: c.get("en", "")
              for c in load_json(root, LEGACY).get("clips", [])}
    parts = []
    for rel in MIRRORS:
        with open(os.path.join(root, rel), encoding="utf-8") as fh:
            parts.append(fh.read())
    mirror_src = "\n".join(parts)
    check_ids(clips, set(legacy), problems)
    check_mirror(clips + reuse, mirror_src, problems)
    check_reuse(reuse, legacy, problems)
    check_mp3([c["id"] for c in clips + reuse],
              root, allow_missing, problems)
    check_durations(root, problems)
    check_voice(root, allow_missing, problems)
    return problems


def main(argv: list) -> int:
    allow_missing = "--allow-missing" in argv
    unknown = [a for a in argv if a != "--allow-missing"]
    if unknown:
        print(f"check-isee-clips-en: option inconnue {unknown[0]}",
              file=sys.stderr)
        return 2
    problems = failures(repo_root(), allow_missing)
    for problem in problems:
        print(f"check-isee-clips-en: {problem}", file=sys.stderr)
    if problems:
        print(f"check-isee-clips-en: {len(problems)} problème(s)",
              file=sys.stderr)
        return 1
    print("check-isee-clips-en: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
