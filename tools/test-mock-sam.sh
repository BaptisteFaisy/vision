#!/usr/bin/env bash
# Banc du faux serveur SAM (`tools/mock-sam.py`) : hors téléphone, hors GPU.
# Démarre le mock sur un port libre, lui envoie de vraies requêtes HTTP `POST /segment`
# (image JPEG 64x48 embarquée en base64 ci-dessous) et contrôle la réponse champ par
# champ : dimensions, mask_encoding, 2-4 objets, libellés, scores, boîtes, masque PNG
# gris décodable, validation d'entrée, latence < 100 ms. Puis arrête le mock.
# Recette téléphone correspondante : `adb reverse tcp:8099 tcp:8099` puis
# `sam31.endpoint=http://127.0.0.1:8099` dans `local.properties` avant le build.
# Sortie : 0 si tout est conforme, 1 sinon, 2 sur argument inconnu.
set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)" || exit 1
. "${HERE}/../scripts/lib/common.sh" || exit 1

std_args test-mock-sam.sh "Usage : bash tools/test-mock-sam.sh
Vérifie tools/mock-sam.py en localhost : santé, contrat /segment, latence."
[[ $# -gt 0 ]] && exit 2

require_cmd python3 "Python 3 (bibliothèque standard) est requis."
require_cmd base64 "L'outil base64 est requis."

TMP="$(mktemp -d "${TMPDIR:-/tmp}/test-mock-sam.XXXXXX")" || exit 1
LOG="${TMP}/mock.log"
IMG="${TMP}/frame.jpg"
VERIFY="${TMP}/verify_contract.py"
MOCK_PID=""
cleanup() {
  if [[ -n "$MOCK_PID" ]]; then
    kill "$MOCK_PID" 2>/dev/null
    wait "$MOCK_PID" 2>/dev/null
  fi
  safe_rm_rf "$TMP"
}
trap cleanup EXIT

# Image de test : JPEG 64x48 embarqué (aucune dépendance pour la créer).
tr -d '\n' <<'B64' | base64 -d >"$IMG" || die "décodage base64 de l'image de test impossible"
/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcU
FhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgo
KCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCAAwAEADASIA
AhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA
AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3
ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWm
p6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEA
AwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSEx
BhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElK
U1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3
uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDMoor0
zwd/yLlp/wAD/wDQ2q82zL+zaKrcvNd23t0b7PscmGoe3ly3seZ0V7NRXz3+uH/Tn/yb/wC1O3+y
/wC/+H/BPGaK9mrxmvayfOP7T5/c5eW3W+9/JdjkxWF+r21vcKKKK9s5Qr0zwd/yLlp/wP8A9Dav
M67nw1rum2ei21vc3OyVN25djHGWJ7D3r5ziehVr4SMaUXJ8y2V+j7Hdl84wqNydtP8AI62isX/h
J9I/5+//ACE/+FH/AAk+kf8AP3/5Cf8Awr4X+zcZ/wA+Zf8AgL/yPY+sUv5l96NqvGa9M/4SfSP+
fv8A8hP/AIV5nX13CuGrUPa+2g435d0137nmZjUjPl5Xff8AQKKKK+vPMCiiigAooooAKKKKACii
igD/2Q==
B64
[[ -s "$IMG" ]] || die "image de test vide"

python3 "$HERE/mock-sam.py" --port 0 --host 127.0.0.1 >"$LOG" 2>&1 &
MOCK_PID=$!

BASE=""
for _ in $(seq 1 100); do
  BASE="$(grep -m1 -o 'http://127\.0\.0\.1:[0-9]*' "$LOG" 2>/dev/null || true)"
  [[ -n "$BASE" ]] && break
  sleep 0.1
done
if [[ -z "$BASE" ]]; then
  cat "$LOG"
  die "le mock n'a pas annoncé son port en 10 s"
fi
printf 'mock : %s (pid %s)\n' "$BASE" "$MOCK_PID"

cat >"$VERIFY" <<'PY'
"""Contrôle champ par champ de la réponse du mock SAM (stdlib seule)."""
import base64
import json
import struct
import sys
import time
import urllib.error
import urllib.request
import zlib

OK = 0
KO = 0


def check(condition, name, detail=""):
    global OK, KO
    if condition:
        OK += 1
        print(f"  ok   {name}")
    else:
        KO += 1
        print(f"  KO   {name} {detail}".rstrip())


def call(base, path, payload=None):
    """Une vraie requête HTTP : (code, corps JSON ou None, durée en secondes)."""
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"Content-Type": "application/json"} if data is not None else {}
    request = urllib.request.Request(base + path, data=data, headers=headers)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body, code = response.read(), response.status
    except urllib.error.HTTPError as error:
        body, code = error.read(), error.code
    elapsed = time.perf_counter() - started
    try:
        return code, json.loads(body.decode()), elapsed
    except (UnicodeDecodeError, json.JSONDecodeError):
        return code, None, elapsed


def png_gray(data):
    """(largeur, hauteur, niveaux présents) d'un PNG gris 8 bits, sinon None."""
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        return None
    width, height = struct.unpack(">II", data[16:24])
    idat, pos = b"", 8
    while pos + 12 <= len(data):
        (size,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        if kind == b"IDAT":
            idat += data[pos + 8:pos + 8 + size]
        pos += 12 + size
    try:
        raw = zlib.decompress(idat)
    except zlib.error:
        return None
    if len(raw) != height * (1 + width):  # une ligne = 1 octet de filtre + largeur
        return None
    return width, height, frozenset(raw)


def check_mask(obj, index):
    """Masque de l'objet : base64 → PNG gris à la taille de l'image, non vide."""
    try:
        raw = base64.b64decode(obj.get("mask_b64") or "", validate=True)
    except (ValueError, TypeError):
        check(False, f"masque {index} : base64 valide")
        return
    decoded = png_gray(raw)
    check(decoded is not None, f"masque {index} : PNG gris 8 bits décodable", f"{len(raw)} octets")
    if decoded is None:
        return
    width, height, levels = decoded
    check((width, height) == (64, 48), f"masque {index} : taille image", f"{width}x{height}")
    check(bool(levels - {0}), f"masque {index} : pixels segmentés", f"niveaux={sorted(levels)}")


def check_object(obj, index):
    """Un objet : libellé, score, boîte dans l'image, masque."""
    label = obj.get("label")
    check(isinstance(label, str) and label.strip() != "", f"objet {index} : label non vide", repr(label))
    score = obj.get("score")
    check(isinstance(score, (int, float)) and 0 < score <= 1, f"objet {index} : score 0-1", repr(score))
    box = obj.get("box")
    ok_box = (isinstance(box, list) and len(box) == 4
              and all(isinstance(v, (int, float)) for v in box)
              and 0 <= box[0] < box[2] <= 64 and 0 <= box[1] < box[3] <= 48)
    check(ok_box, f"objet {index} : boîte [x1,y1,x2,y2] dans l'image", repr(box))
    check_mask(obj, index)


def best_duration(base, payload, count=5):
    """Meilleure durée (s) sur [count] appels identiques : le bruit ne décide pas."""
    durations = [call(base, "/segment", payload)[2] for _ in range(count)]
    return min(durations), durations


def check_segment(base, image_b64, prompts=None):
    code, body, _ = call(base, "/segment", {"image_b64": image_b64, "prompts": prompts})
    check(code == 200, "POST /segment → 200", f"code={code}")
    if code != 200 or body is None:
        return
    check((body.get("width"), body.get("height")) == (64, 48),
          "width/height lus dans l'en-tête JPEG, sans décodage",
          f"{body.get('width')}x{body.get('height')}")
    check(body.get("mask_encoding") == "png", "mask_encoding = png (attendu par le client)",
          repr(body.get("mask_encoding")))
    objects = body.get("objects") or []
    check(2 <= len(objects) <= 4, "2 à 4 masques", f"{len(objects)}")
    check(body.get("count") == len(objects), "count cohérent avec objects",
          f"count={body.get('count')} objects={len(objects)}")
    check(all(isinstance(o.get("label"), str) for o in objects), "un libellé par masque")
    for index, obj in enumerate(objects):
        check_object(obj, index)


def main(base, image_path):
    """Santé, contrat nominal, concepts, entrées limites, latence."""
    with open(image_path, "rb") as handle:
        image_b64 = base64.b64encode(handle.read()).decode()

    code, health, _ = call(base, "/health")
    check(code == 200 and health is not None and health.get("ok") is True,
          "GET /health → ok:true", f"code={code}")

    print("-- POST /segment (contrat nominal)")
    check_segment(base, image_b64)

    print("-- POST /segment (concepts imposés)")
    code, body, _ = call(base, "/segment", {"image_b64": image_b64, "prompts": ["personne", "vélo"]})
    labels = [obj.get("label") for obj in (body or {}).get("objects") or []]
    check(code == 200 and labels, "les concepts demandés sont servis", repr(labels))
    check(all(label in ("personne", "vélo") for label in labels),
          "les libellés suivent les prompts", repr(labels))

    print("-- entrées limites")
    default_image = base64.b64encode(b"pas une image" * 8).decode()
    code, body, _ = call(base, "/segment", {"image_b64": default_image})
    check(code == 200 and (body or {}).get("width") == 640,
          "contenu non image accepté (dimensions par défaut)", f"code={code}")
    big, _ = best_duration(base, {"image_b64": default_image})
    check(0 < big < 0.100, "frame 640x480 servie en < 100 ms", f"{big * 1000:.2f} ms")

    code, _, _ = call(base, "/segment", {"image_b64": ""})
    check(code == 422, "image_b64 vide → 422", f"code={code}")

    code, _, _ = call(base, "/inconnu")
    check(code == 404, "chemin inconnu → 404", f"code={code}")

    print("-- latence (5 appels identiques, image 64x48)")
    best, durations = best_duration(base, {"image_b64": image_b64, "prompts": None})
    check(0 < best < 0.100, "meilleure réponse /segment < 100 ms",
          f"{best * 1000:.2f} ms — toutes : " + ", ".join(f"{d * 1000:.1f}" for d in durations))

    print(f"\nbilan : {OK} ok, {KO} KO (latence min {best * 1000:.2f} ms)")
    return 1 if KO else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
PY

python3 "$VERIFY" "$BASE" "$IMG"
RC=$?

printf '\n-- journal du mock (dernières lignes)\n'
tail -4 "$LOG" | sed 's/^/   /'
if grep -q 'POST /segment .* masques' "$LOG"; then
  printf '  ok   le mock journalise chaque requête (horodatage, taille, masques, durée)\n'
else
  printf '  KO   aucune ligne de journal POST /segment\n'
  RC=1
fi

[[ "$RC" == "0" ]] || printf '\nÉCHEC du banc : voir les lignes KO ci-dessus.\n'
exit "$RC"