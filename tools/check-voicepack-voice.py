#!/usr/bin/env python3
"""Audit de la VOIX du pack : chaque MP3 est-il bien en voix Laure (ElevenLabs) ?

Le garde `check-voicepack.py` vérifie que le catalogue colle au code et que les
MP3 existent — il ne dit rien de la voix qui les a prononcés. Ce script le
vérifie **par le son** : pour chaque entrée du catalogue, il synthétise le même
texte avec la voix du pack (`LAURE_VOICE_ID`) et compare les deux sons.

Métrique (sans modèle de reconnaissance du locuteur) : profil d'énergie
log-espacé sur 24 bandes (80 Hz – 7 kHz), normalisé puis comparé en cosinus,
plus la fréquence fondamentale médiane. Calibré le 2026-11-01 sur ce pack :

    Laure vs Laure (même texte, régénéré)   0,987 – 0,999   -> même voix
    Laure vs Rachel (autre voix, même texte) 0,938 – 0,953   -> voix différente

Seuil par défaut : 0,980 (entre les deux, plus près du pire cas Laure constaté).
Un fichier sous le seuil est listé « À REFAIRE » : le régénérer avec
`ELEVENLABS_API_KEY=… python3 tools/gen-voicepack.py --regenerate` (ou sans
`--regenerate` après avoir supprimé le fichier).

Usage (outil MANUEL, hors CI : réseau + clé + numpy + ffmpeg) :

    ELEVENLABS_API_KEY=… python3 tools/check-voicepack-voice.py [options]

      --id <id>         n'auditer qu'une entrée du catalogue (répétable)
      --limit N         s'arrêter après N fichiers (essai rapide)
      --intro           auditer aussi res/intro_isee.mp3 (modèle eleven_v3)
      --threshold X     seuil de similarité (défaut 0,980)
      --keep-refs DIR   garder les synthèses de référence (sinon temporaire)

La clé ne transite que par l'environnement (jamais affichée ni stockée).
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

BASE_URL = "https://api.elevenlabs.io/v1"
MODEL = "eleven_v4_turbo"      # même modèle que gen-voicepack.py
INTRO_MODEL = "eleven_v3"      # l'intro embarquée a été générée en v3
OUT_FORMAT = "mp3_44100_128"
VOICE_SETTINGS = {
    "stability": 0.5,
    "similarity_boost": 0.75,
    "style": 0.0,
    "use_speaker_boost": True,
}
SR = 16000
BANDS = 24


def repo_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def pack_voice(root: str) -> str:
    """Voix du pack : `assets/vp_voice.txt`, à défaut LAURE_VOICE_ID de Settings.kt."""
    stamp = pathlib.Path(root, "app/src/main/assets/vp_voice.txt")
    if stamp.is_file():
        voice = stamp.read_text(encoding="utf-8").strip()
        if voice:
            return voice
    src = pathlib.Path(root, "core/src/main/java/dev/sam3vision"
                             "/core/settings/Settings.kt").read_text(encoding="utf-8")
    mat = re.search(r'LAURE_VOICE_ID\s*=\s*"([^"]+)"', src)
    if not mat:
        sys.exit("check-voicepack-voice: voix du pack introuvable")
    return mat.group(1)


def synthesize(key: str, voice: str, text: str, model: str, dest: pathlib.Path,
               tries: int = 4) -> bool:
    body = json.dumps({"text": text, "model_id": model,
                       "voice_settings": VOICE_SETTINGS}).encode("utf-8")
    url = f"{BASE_URL}/text-to-speech/{voice}?output_format={OUT_FORMAT}"
    for attempt in range(tries):
        req = urllib.request.Request(
            url, data=body,
            headers={"xi-api-key": key, "Content-Type": "application/json"},
            method="POST")
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                dest.write_bytes(resp.read())
            return True
        except urllib.error.HTTPError as err:
            if err.code in (429, 500, 503):
                time.sleep(2 + 3 * attempt)
                continue
            print(f"  synthèse refusée ({err.code}) pour {dest.name}", file=sys.stderr)
            return False
        except urllib.error.URLError:
            time.sleep(2 + 3 * attempt)
    return False


def load(path: pathlib.Path, np):
    raw = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-i", str(path), "-f", "f32le",
         "-ac", "1", "-ar", str(SR), "-"],
        capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32)


def fingerprint(path: pathlib.Path, np):
    """(profil spectral normalisé, F0 médiane en Hz, durée en s)."""
    wave = load(path, np)
    if len(wave) < SR // 10:
        return None
    wave = wave / (np.abs(wave).max() + 1e-9)
    frame, hop = 512, 160
    if len(wave) < frame:
        wave = np.pad(wave, (0, frame - len(wave)))
    count = 1 + (len(wave) - frame) // hop
    index = np.arange(frame)[None, :] + hop * np.arange(count)[:, None]
    frames = wave[index] * np.hanning(frame)
    power = np.abs(np.fft.rfft(frames, axis=1)) ** 2
    freqs = np.fft.rfftfreq(frame, 1 / SR)
    edges = np.geomspace(80, 7000, BANDS + 1)
    profile = np.zeros(BANDS)
    for band in range(BANDS):
        sel = (freqs >= edges[band]) & (freqs < edges[band + 1])
        profile[band] = power[:, sel].sum() if sel.any() else 0.0
    profile = np.log10(profile + 1e-6)
    profile -= profile.mean()
    profile /= (np.linalg.norm(profile) + 1e-9)
    energy = (frames ** 2).sum(axis=1)
    voiced = frames[energy > np.percentile(energy, 60)][:400]
    pitches = []
    for row in voiced:
        row = row - row.mean()
        auto = np.correlate(row, row, mode="full")[len(row) - 1:]
        if auto[0] <= 0:
            continue
        auto /= auto[0]
        low, high = int(SR / 400), int(SR / 70)
        if high >= len(auto):
            continue
        lag = low + int(np.argmax(auto[low:high]))
        if auto[lag] > 0.3:
            pitches.append(SR / lag)
    pitch = float(np.median(pitches)) if pitches else float("nan")
    return profile, pitch, len(wave) / SR


def compare(ref: pathlib.Path, pack: pathlib.Path, np):
    a, b = fingerprint(ref, np), fingerprint(pack, np)
    if a is None or b is None:
        return None
    return {
        "sim": float(np.dot(a[0], b[0])),
        "f0_ref": a[1],
        "f0_pack": b[1],
        "duree_pack": b[2],
    }


def jobs_for(root: str, ids: list, intro: bool) -> list:
    catalog = json.load(open(pathlib.Path(root, "tools/voicepack-catalog.json"),
                          encoding="utf-8"))["clips"]
    raw = pathlib.Path(root, "app/src/main/res/raw")
    jobs = []
    for clip in catalog:
        if ids and clip["id"] not in ids:
            continue
        for lang in ("fr", "en"):
            text = clip.get(lang)
            mp3 = raw / f"vp_{clip['id']}_{lang}.mp3"
            if text and mp3.is_file():
                jobs.append(dict(id=clip["id"], lang=lang, text=text, mp3=mp3,
                                 model=MODEL))
    if intro:
        src = pathlib.Path(root, "app/src/main/java/dev/sam3vision/app/IntroLogic.kt")
        text = re.search(r'ISEE_INTRO_TEXT\s*=\s*((?:\s*"[^"]*"\s*\+?)+)',
                         src.read_text(encoding="utf-8"))
        if text:
            spoken = "".join(re.findall(r'"([^"]*)"', text.group(1)))
            mp3 = raw / "intro_isee.mp3"
            if mp3.is_file():
                jobs.append(dict(id="intro", lang="en", text=spoken, mp3=mp3,
                                 model=INTRO_MODEL))
    return jobs


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--id", action="append", default=[])
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--intro", action="store_true")
    parser.add_argument("--threshold", type=float, default=0.980)
    parser.add_argument("--keep-refs", default="")
    return parser.parse_args()


def audit(jobs: list, key: str, voice: str, refs: pathlib.Path, np) -> list:
    """Comparer chaque MP3 à sa synthèse de référence ; `sim = None` si échec."""
    results = []
    for index, job in enumerate(jobs, 1):
        ref = refs / f"ref_{job['id']}_{job['lang']}_{job['model']}.mp3"
        if not ref.is_file() and not synthesize(key, voice, job["text"], job["model"], ref):
            results.append(dict(job, sim=None, err="synthèse"))
            continue
        measured = compare(ref, job["mp3"], np)
        if measured is None:
            results.append(dict(job, sim=None, err="audio illisible"))
            continue
        results.append(dict(job, err="", **measured))
        if index % 20 == 0:
            print(f"  {index}/{len(jobs)}")
    return results


def report(results: list, threshold: float) -> bool:
    """Bilan lisible ; `False` si un fichier est sous le seuil ou en échec."""
    ok = [row for row in results if not row["err"]]
    bad = [row for row in ok if row["sim"] < threshold]
    failed = [row for row in results if row["err"]]
    ok.sort(key=lambda row: row["sim"])
    print(f"\n{len(ok)} comparés | >= {threshold} : {len(ok) - len(bad)}"
          f" | sous le seuil : {len(bad)} | en erreur : {len(failed)}")
    if ok:
        print("les 5 moins ressemblants :")
        for row in ok[:5]:
            print(f"  {row['sim']:.4f}  {row['id']}_{row['lang']}  "
                  f"« {row['text'][:44]} »")
    for row in bad:
        print(f"À REFAIRE : {row['id']}_{row['lang']} (sim {row['sim']:.4f}) "
              f"« {row['text']} »")
    for row in failed:
        print(f"ÉCHEC : {row['id']}_{row['lang']} — {row['err']}")
    return not bad and not failed


def main() -> None:
    args = parse_args()
    key = os.environ.get("ELEVENLABS_API_KEY", "").strip()
    if not key:
        sys.exit("ELEVENLABS_API_KEY absente de l'environnement (jamais en argument).")
    try:
        import numpy as np
    except ImportError:
        sys.exit("numpy requis pour l'audit (pip install numpy) ; ffmpeg aussi.")
    root = repo_root()
    voice = pack_voice(root)
    jobs = jobs_for(root, args.id, args.intro)
    if args.limit:
        jobs = jobs[:args.limit]
    if not jobs:
        sys.exit("check-voicepack-voice: aucun MP3 à auditer")
    refs = pathlib.Path(args.keep_refs) if args.keep_refs else pathlib.Path(
        tempfile.mkdtemp(prefix="voix-audit-"))
    refs.mkdir(parents=True, exist_ok=True)
    print(f"audit de {len(jobs)} MP3 contre la voix {voice} (réfs : {refs})")
    if not report(audit(jobs, key, voice, refs, np), args.threshold):
        sys.exit(1)


if __name__ == "__main__":
    main()
