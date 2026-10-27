#!/usr/bin/env python3
"""Mesure du tracker SAM 3.1 depuis le téléphone (adb + curl natif, keep-alive).

Le PC prépare les payloads et ouvre la session ; le téléphone n'exécute que la boucle
HTTP, en **une seule invocation curl** (`--next`) pour réutiliser la connexion TLS —
comme le fait l'app avec OkHttp. Chaque transfert est chronométré par curl
(`time_total`, `time_appconnect`), et la réponse JSON est gardée pour lire `elapsed_ms`.

    python3 /tmp/phone_bench.py --url https://…modal.run --frames 60 --size 640x360
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bench_track import Poster, extract_frames, jpeg_b64  # noqa: E402

REMOTE = "/data/local/tmp/trackbench"
SERIAL = "2c6852ac"


DIRECT = False  # True = verrou déjà tenu par l'appelant (phone take)


def adb(*args: str, capture: bool = True) -> str:
    """Commande adb sur le téléphone.

    Par défaut on passe par `~/bin/phone run` (verrou pris/relâché autour de l'appel). En
    mode `--direct`, l'appelant tient déjà le verrou (`phone take`) : on adresse alors adb
    directement, sinon on se bloquerait nous-mêmes.
    """
    prefix = ["adb", "-s", SERIAL] if DIRECT else [
        "/home/baptiste/bin/phone", "run", "--task", "bench tracker SAM 3.1", "--", "adb", "-s", SERIAL]
    command = [*prefix, *args]
    result = subprocess.run(command, capture_output=capture, text=True, timeout=900)
    if result.returncode != 0:
        raise SystemExit(f"adb {args[0]} a échoué ({result.returncode}): {result.stderr[-400:]}")
    return result.stdout if capture else ""


def build_script(frames: int, url: str) -> str:
    """Script shell du téléphone : N POST /frame chaînés dans une seule invocation curl."""
    lines = ["#!/system/bin/sh", f"cd {REMOTE} || exit 1", "curl -s -o /dev/null -w '%{time_total}\\n'"]
    parts = []
    for index in range(1, frames):
        parts.append(f"-s -o out{index:03d}.json -w '%{{time_total}} %{{time_appconnect}}\\n' "
                     f"-X POST -H 'Content-Type: application/json' --data @f{index:03d}.json "
                     f"{url}/frame")
    return "\n".join(lines + ["curl " + " \\\n  --next ".join(parts)] + ["echo DONE"]) + "\n"


def stats(values: list) -> dict:
    """min / médiane / p95 / max (ms)."""
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round(0.95 * (len(ordered) - 1))))
    return {"n": len(ordered), "min": round(ordered[0], 1),
            "median": round(statistics.median(ordered), 1),
            "p95": round(ordered[index], 1), "max": round(ordered[-1], 1)}


def _push_payloads(clip, session_id: str, url: str) -> None:
    """Payloads poussés sur le téléphone (frames + script de mesure)."""
    import tarfile

    with tarfile.open("/tmp/payloads.tar.gz", "w:gz") as archive:
        for index, frame in enumerate(clip[1:], start=1):
            path = f"/tmp/trackbench_{index:03d}.json"
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(json.dumps({"session_id": session_id, "image_b64": jpeg_b64(frame)}))
            archive.add(path, arcname=f"f{index:03d}.json")
    adb("push", "/tmp/payloads.tar.gz", f"{REMOTE}/payloads.tar.gz")
    script = build_script(len(clip), url.rstrip("/"))
    with open("/tmp/trackbench.sh", "w", encoding="utf-8") as handle:
        handle.write(script)
    adb("push", "/tmp/trackbench.sh", f"{REMOTE}/bench.sh")
    adb("shell", f"cd {REMOTE} && tar xzf payloads.tar.gz")
    print(f"payloads poussés ({len(clip) - 1} frames, script {len(script)} o)")


def _run_measures():
    """Une seule invocation curl sur le téléphone, lignes numériques parsées."""
    output = adb("shell", f"sh {REMOTE}/bench.sh")
    rows = [line.split() for line in output.splitlines() if line and line[0].isdigit()]
    if not rows:
        raise SystemExit(f"aucune mesure exploitable: {output[-400:]}")
    total = [float(row[0]) * 1000 for row in rows]
    appconnect = [float(row[1]) * 1000 for row in rows if len(row) > 1]
    return rows, total, appconnect


def _fetch_server_ms(poster, session_id: str) -> list:
    """Ce que le serveur a mesuré : rapatriement des out*.json, best-effort."""
    import tarfile

    server = []
    try:
        adb("shell", f"cd {REMOTE} && tar czf outs.tar.gz out*.json")
        adb("pull", f"{REMOTE}/outs.tar.gz", "/tmp/outs.tar.gz")
        with tarfile.open("/tmp/outs.tar.gz", "r:gz") as archive:
            for member in sorted(archive.getmembers(), key=lambda item: item.name):
                handle = archive.extractfile(member)
                if handle is not None:
                    server.append(float(json.load(handle).get("elapsed_ms", 0)))
    except Exception as exc:  # noqa: BLE001 — un pull raté ne doit pas jeter la mesure réseau
        print(f"rapatriement partiel: {exc}")
    poster.delete(f"/session/{session_id}")
    poster.close()
    return server


def _write_report(args, rows, total, appconnect, server) -> int:
    """Rapport console + JSON : RTT téléphone, appconnect, serveur, verdict p95."""
    report = {"size": args.size, "quality": args.quality, "frames": len(rows),
              "phone_rtt_ms": stats(total), "phone_appconnect_ms": stats(appconnect[1:]) if len(appconnect) > 1 else {},
              "server_ms": stats(server) if server else {},
              "first3": [round(value, 1) for value in total[:3]],
              "over_300": [round(value, 1) for value in total if value > 300],
              "raw_ms": [round(value, 1) for value in total],
              "p95_under_300": stats(total)["p95"] < 300}
    print(json.dumps(report, indent=2))
    with open("/tmp/phone-bench.json", "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--frames", type=int, default=60)
    parser.add_argument("--size", default="640x360")
    parser.add_argument("--capacity", type=int, default=300)
    parser.add_argument("--quality", type=int, default=80)
    parser.add_argument("--direct", action="store_true", help="verrou déjà tenu (phone take)")
    args = parser.parse_args()
    global DIRECT
    DIRECT = args.direct

    import bench_track

    bench_track.JPEG_QUALITY = args.quality
    width, height = (int(value) for value in args.size.lower().split("x"))
    clip = extract_frames("/tmp/clip_720p_300.mp4", width, height, args.frames)
    print(f"{len(clip)} frames {width}x{height} q{args.quality}")

    # 1) session ouverte depuis le PC (le téléphone ne fait que les /frame)
    poster = Poster(args.url, 180.0)
    status, body, rtt = poster.post("/session", {"image_b64": jpeg_b64(clip[0]),
                                                 "prompts": ["person"], "capacity": args.capacity})
    if status != 200:
        raise SystemExit(f"/session HTTP {status}: {str(body)[:300]}")
    session_id = body["session_id"]
    print(f"session {session_id} ouverte en {rtt:.0f} ms, {len(body['objects'])} objet(s), "
          f"serveur {body.get('elapsed_ms')} ms")

    _push_payloads(clip, session_id, args.url)
    rows, total, appconnect = _run_measures()
    server = _fetch_server_ms(poster, session_id)
    return _write_report(args, rows, total, appconnect, server)


if __name__ == "__main__":
    sys.exit(main())