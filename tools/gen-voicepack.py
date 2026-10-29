#!/usr/bin/env python3
"""Génère le pack voix (MP3 ElevenLabs) depuis tools/voicepack-catalog.json.

Usage :
    ELEVENLABS_API_KEY=… python3 tools/gen-voicepack.py [--voice ID] [--force]

- La clé vient de l'environnement, JAMAIS d'un argument/fichier (jamais affichée
  ni stockée). La voix par défaut est lue dans Settings.kt (LAURE_VOICE_ID).
- Mêmes paramètres que l'app (ElevenLabsTtsClient) : eleven_v4_turbo,
  mp3_44100_128, stability 0.5, similarity 0.75, style 0.0, speaker boost.
- Sorties : app/src/main/res/raw/vp_<id>_<lang>.mp3 + assets/vp_voice.txt
  (voix du pack — l'app ignore le pack si l'utilisateur a changé de voix).
- Sans --force, les MP3 existants non vides sont gardés (reprise).
"""
import json
import os
import re
import sys
import urllib.request

BASE_URL = "https://api.elevenlabs.io/v1"
MODEL = "eleven_v4_turbo"
OUT_FORMAT = "mp3_44100_128"


def repo_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def default_voice(root: str) -> str:
    """Voix par défaut de l'app (Settings.kt) — le pack parle comme elle."""
    path = os.path.join(
        root, "core/src/main/java/dev/sam3vision/core/settings/Settings.kt")
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    mat = re.search(r'LAURE_VOICE_ID\s*=\s*"([^"]+)"', src)
    if not mat:
        sys.exit("gen-voicepack: LAURE_VOICE_ID introuvable dans Settings.kt")
    return mat.group(1)


def synthesize(api_key: str, voice: str, text: str) -> bytes:
    url = (f"{BASE_URL}/text-to-speech/{voice}"
           f"?output_format={OUT_FORMAT}")
    body = json.dumps({
        "text": text,
        "model_id": MODEL,
        "voice_settings": {
            "stability": 0.5,
            "similarity_boost": 0.75,
            "style": 0.0,
            "use_speaker_boost": True,
        },
    }).encode("utf-8")
    req = urllib.request.Request(
        url, data=body,
        headers={"xi-api-key": api_key, "Content-Type": "application/json"},
        method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    if not data or data[:1] in b"{[<" or data.startswith(b"Bad Gateway"):
        raise RuntimeError(f"réponse non-audio ({len(data)} octets)")
    return data


def parse_args(argv: list) -> tuple:
    """(voix, force) depuis argv ; erreur d'usage = SystemExit(2)."""
    voice = ""
    force = False
    args = list(argv)
    while args:
        arg = args.pop(0)
        if arg == "--voice" and args:
            voice = args.pop(0)
        elif arg == "--force":
            force = True
        else:
            print(f"gen-voicepack: option inconnue {arg}", file=sys.stderr)
            raise SystemExit(2)
    return voice, force


def main(argv: list) -> int:
    api_key = os.environ.get("ELEVENLABS_API_KEY", "")
    if not api_key:
        print("gen-voicepack: ELEVENLABS_API_KEY manquante", file=sys.stderr)
        return 2
    root = repo_root()
    voice, force = parse_args(argv)
    voice = voice or default_voice(root)
    with open(os.path.join(root, "tools/voicepack-catalog.json"),
              encoding="utf-8") as fh:
        catalog = json.load(fh)["clips"]
    raw_dir = os.path.join(root, "app/src/main/res/raw")
    assets_dir = os.path.join(root, "app/src/main/assets")
    os.makedirs(raw_dir, exist_ok=True)
    os.makedirs(assets_dir, exist_ok=True)
    done, kept, failed = 0, 0, []
    for clip in catalog:
        for lang in ("fr", "en"):
            text = clip.get(lang)
            if not text:
                continue
            name = f"vp_{clip['id']}_{lang}.mp3"
            dest = os.path.join(raw_dir, name)
            if not force and os.path.isfile(dest) and os.path.getsize(dest):
                kept += 1
                continue
            try:
                with open(dest, "wb") as fh:
                    fh.write(synthesize(api_key, voice, text))
                done += 1
                print(f"gen-voicepack: {name} ({len(text)} car.)")
            except Exception as exc:  # noqa: BLE001 - on accumule, bilan final
                failed.append(f"{name}: {exc}")
    with open(os.path.join(assets_dir, "vp_voice.txt"), "w",
              encoding="utf-8") as fh:
        fh.write(voice.strip() + "\n")
    print(f"gen-voicepack: {done} générés, {kept} gardés, "
          f"{len(failed)} échecs, voix {voice[:4]}…")
    for failure in failed:
        print(f"gen-voicepack: ÉCHEC {failure}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
