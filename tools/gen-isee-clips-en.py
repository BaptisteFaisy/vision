#!/usr/bin/env python3
"""Génère les clips anglais SPEC v4 depuis tools/isee-clips-en-catalog.json.

Usage :
    ELEVENLABS_API_KEY=… python3 tools/gen-isee-clips-en.py [--voice ID] [--regenerate]

- La clé vient de l'environnement, JAMAIS d'un argument/fichier (jamais affichée
  ni stockée). La voix par défaut est lue dans Settings.kt (LAURE_VOICE_ID).
- Mêmes paramètres que l'app (ElevenLabsTtsClient) : eleven_v4_turbo,
  mp3_44100_128, stability 0.5, similarity 0.75, style 0.0, speaker boost.
- Sorties : app/src/main/res/raw/vp_<id>_en.mp3 + assets/vp_voice.txt
  (même convention que gen-voicepack.py : un seul pack, une seule voix).
- Sans --regenerate, les MP3 existants non vides sont gardés (reprise).
- La section 'reuse' du catalogue n'est jamais régénérée (MP3 déjà embarqués).
- Refuse de mélanger deux voix : si vp_voice.txt existe avec une autre voix,
  arrêt immédiat (régénérer tout le pack explicitement plutôt que moitié).
"""
import json
import os
import re
import sys
import urllib.request

BASE_URL = "https://api.elevenlabs.io/v1"
MODEL = "eleven_v4_turbo"
OUT_FORMAT = "mp3_44100_128"
CATALOG = "tools/isee-clips-en-catalog.json"


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
        sys.exit("gen-isee-clips-en: LAURE_VOICE_ID introuvable dans Settings.kt")
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
    """(voix, regenere) depuis argv ; erreur d'usage = SystemExit(2)."""
    voice = ""
    regenere = False
    args = list(argv)
    while args:
        arg = args.pop(0)
        if arg == "--voice" and args:
            voice = args.pop(0)
        elif arg == "--regenerate":
            regenere = True
        else:
            print(f"gen-isee-clips-en: option inconnue {arg}", file=sys.stderr)
            raise SystemExit(2)
    return voice, regenere


def main(argv: list) -> int:
    api_key = os.environ.get("ELEVENLABS_API_KEY", "")
    if not api_key:
        print("gen-isee-clips-en: ELEVENLABS_API_KEY manquante", file=sys.stderr)
        return 2
    root = repo_root()
    voice, regenere = parse_args(argv)
    voice = voice or default_voice(root)
    stamp_path = os.path.join(root, "app/src/main/assets/vp_voice.txt")
    if os.path.isfile(stamp_path):
        stamped = open(stamp_path, encoding="utf-8").read().strip()
        if stamped and stamped != voice.strip():
            print("gen-isee-clips-en: vp_voice.txt désigne une autre voix "
                  f"({stamped[:4]}… ≠ {voice[:4]}…) : régénérer tout le pack "
                  "explicitement, pas moitié", file=sys.stderr)
            return 2
    with open(os.path.join(root, CATALOG), encoding="utf-8") as fh:
        catalog = json.load(fh)["clips"]
    raw_dir = os.path.join(root, "app/src/main/res/raw")
    assets_dir = os.path.join(root, "app/src/main/assets")
    os.makedirs(raw_dir, exist_ok=True)
    os.makedirs(assets_dir, exist_ok=True)
    done, kept, failed = 0, 0, []
    for clip in catalog:
        text = clip.get("en", "")
        if not text:
            continue
        name = f"vp_{clip['id']}_en.mp3"
        dest = os.path.join(raw_dir, name)
        if not regenere and os.path.isfile(dest) and os.path.getsize(dest):
            kept += 1
            continue
        try:
            with open(dest, "wb") as fh:
                fh.write(synthesize(api_key, voice, text))
            done += 1
            print(f"gen-isee-clips-en: {name} ({len(text)} car.)")
        except Exception as exc:  # noqa: BLE001 - on accumule, bilan final
            failed.append(f"{name}: {exc}")
    with open(stamp_path, "w", encoding="utf-8") as fh:
        fh.write(voice.strip() + "\n")
    print(f"gen-isee-clips-en: {done} générés, {kept} gardés, "
          f"{len(failed)} échecs, voix {voice[:4]}…")
    for failure in failed:
        print(f"gen-isee-clips-en: ÉCHEC {failure}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
