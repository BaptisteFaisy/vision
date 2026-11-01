#!/usr/bin/env python3
"""Rejoue un trajet GPS sur un émulateur Android, à cadence fixe (défaut 1 Hz).

Lit un CSV (`lat,lon`, en-tête toléré, `;` ou espaces acceptés) ou un GPX
(`<trkpt lat="…" lon="…">`) et pousse chaque point par le **receiver de
simulation de l'app debug** (précision annoncée, donc le guidage iSee accepte
le fix) ; `--mode geofix` retombe sur `adb emu geo fix`, dont la position
n'annonce aucune précision — iSee jette alors ces fixes sans rien dire.

Réutilisable seul, sans le harnais :

    scripts/sim/route-replay.py --route balade.csv --serial emulator-5556
    scripts/sim/route-replay.py --route balade.gpx --dry-run

Ne bloque jamais indéfiniment : chaque appel adb a un timeout (--adb-timeout,
10 s par défaut), deux échecs consécutifs arrêtent le rejeu (code 1), et
--max-seconds borne la durée totale.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import time

PKG_OK = 0
PKG_USAGE = 2

# Contrat de `SimLocationDebugReceiver` (app/src/debug, build debug seulement).
SIM_ACTION = "dev.sam3vision.app.sim.LOCATION"
SIM_CLEAR = "dev.sam3vision.app.sim.LOCATION_CLEAR"
SIM_ACCURACY_M = "8"

# Un `<trkpt …>` GPX : attributs dans n'importe quel ordre, flottants signés.
GPX_POINT = re.compile(r"<trk(?:pt|e|kseg)\b", re.IGNORECASE)
GPX_ATTR = re.compile(r'\b(lat|lon)\s*=\s*"([^"]+)"', re.IGNORECASE)


def die(message, code=1):
    """Arrêt propre : message sur stderr, code de sortie explicite."""
    print("ERREUR : %s" % message, file=sys.stderr)
    sys.exit(code)


def coord(value, label, index):
    """Convertit un champ en flottant, refuse un point hors du globe."""
    try:
        number = float(value)
    except (TypeError, ValueError):
        die("point %d : %s illisible ('%s')" % (index, label, value), PKG_USAGE)
    limit = 90.0 if label == "lat" else 180.0
    if not -limit <= number <= limit:
        die("point %d : %s hors bornes (%s)" % (index, label, value), PKG_USAGE)
    return number


def parse_csv(text):
    """Points d'un CSV : première paire de nombres de chaque ligne."""
    points = []
    for index, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = [f.strip() for f in re.split(r"[,;\s]+", line) if f.strip()]
        if len(fields) < 2:
            die("ligne %d : il faut 'lat,lon' (reçu '%s')" % (index, line), PKG_USAGE)
        try:
            float(fields[0]), float(fields[1])
        except ValueError:
            if not points:  # en-tête "lat,lon" ou "latitude,longitude"
                continue
            die("ligne %d : 'lat,lon' attendu (reçu '%s')" % (index, line), PKG_USAGE)
        points.append((coord(fields[0], "lat", len(points) + 1),
                       coord(fields[1], "lon", len(points) + 1)))
    return points


def parse_gpx(text):
    """Points d'un GPX : lat/lon de chaque `<trkpt>`, lus dans la balise."""
    points = []
    for match in re.finditer(r"<trkpt\b([^>]*)>", text, re.IGNORECASE):
        attrs = dict((k.lower(), v) for k, v in GPX_ATTR.findall(match.group(1)))
        if "lat" not in attrs or "lon" not in attrs:
            continue
        index = len(points) + 1
        points.append((coord(attrs["lat"], "lat", index),
                       coord(attrs["lon"], "lon", index)))
    return points


def read_route(path):
    """Lit le trajet, GPX si le contenu ou le suffixe le dit, CSV sinon."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError as error:
        die("trajet illisible : %s (%s)" % (path, error.strerror))
    is_gpx = path.lower().endswith((".gpx", ".xml")) or GPX_POINT.search(text)
    points = parse_gpx(text) if is_gpx else parse_csv(text)
    if not points:
        die("aucun point GPS dans %s" % path, PKG_USAGE)
    return points


def resolve_adb(given):
    """adb : --adb, puis $ADB, puis le PATH, puis le SDK Android."""
    for candidate in (given, os.environ.get("ADB")):
        if candidate:
            if not (os.path.isfile(candidate) and os.access(candidate, os.X_OK)):
                die("ADB=%s n'est pas un exécutable" % candidate)
            return candidate
    found = shutil.which("adb")
    if found:
        return found
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.expanduser("~/Android/Sdk"),
                 os.path.expanduser("~/.local/opt/android-sdk")):
        if root:
            candidate = os.path.join(root, "platform-tools", "adb")
            if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                return candidate
    die("adb introuvable : passez --adb, $ADB ou installez les platform-tools")


def adb_run(adb, serial, argv, timeout_s):
    """Lance `adb -s <série> …` ; renvoie (code, sortie combinée). `None` si timeout."""
    try:
        done = subprocess.run([adb, "-s", serial] + argv, capture_output=True,
                              timeout=timeout_s, check=False)
    except subprocess.TimeoutExpired:
        return None, "aucune réponse en %ss" % timeout_s
    except OSError as error:
        die("adb n'a pas pu être lancé : %s" % error.strerror)
    return done.returncode, (done.stdout + done.stderr).decode("utf-8", "replace").strip()


def has_sim_receiver(adb, serial, pkg, timeout_s):
    """Vrai si l'APK installé porte le receiver de position simulée (build debug)."""
    code, out = adb_run(adb, serial, ["shell", "dumpsys", "package", pkg], timeout_s)
    return code == 0 and SIM_ACTION in out


def send_broadcast(adb, serial, pkg, point, timeout_s):
    """Pousse un point par le receiver debug (précision annoncée). True si accepté."""
    lat, lon = point
    argv = ["shell", "am", "broadcast", "-a", SIM_ACTION, "-p", pkg,
            "--ef", "lat", "%.6f" % lat, "--ef", "lon", "%.6f" % lon,
            "--ef", "acc", SIM_ACCURACY_M]
    code, out = adb_run(adb, serial, argv, timeout_s)
    if code != 0 or "Broadcast completed" not in out:
        print("ATTENTION : broadcast de position refusé (%s)"
              % (out or "code %s" % code), file=sys.stderr)
        return False
    return True


def send_geofix(adb, serial, point, timeout_s):
    """Pousse un point par `adb emu geo fix` (lon, lat). True si accepté."""
    lat, lon = point
    argv = ["emu", "geo", "fix", "%.6f" % lon, "%.6f" % lat]
    code, out = adb_run(adb, serial, argv, timeout_s)
    if code != 0:
        print("ATTENTION : geo fix refusé (%s)" % (out or "code %s" % code), file=sys.stderr)
        return False
    return True


def choose_mode(wanted, adb, serial, pkg, timeout_s):
    """Mode effectif : `auto` prend le receiver s'il est là, sinon geo fix (averti)."""
    if wanted != "auto":
        return wanted
    if has_sim_receiver(adb, serial, pkg, timeout_s):
        print("position : receiver de simulation (précision %s m)" % SIM_ACCURACY_M)
        return "broadcast"
    print("ATTENTION : APK sans receiver de simulation (build release ?) → 'adb emu geo "
          "fix', dont la position n'annonce pas de précision : iSee rejette ces fixes",
          file=sys.stderr)
    return "geofix"


def make_sender(mode, pkg):
    """Rend l'envoi d'un point, selon le mode retenu."""
    if mode == "broadcast":
        return lambda adb, serial, point, timeout_s: send_broadcast(
            adb, serial, pkg, point, timeout_s)
    return send_geofix


def clear(adb, args, mode):
    """Retire le test provider à la fin (les fixes réels reprennent). Silencieux."""
    if mode != "broadcast":
        return
    adb_run(adb, args.serial,
            ["shell", "am", "broadcast", "-a", SIM_CLEAR, "-p", args.pkg],
            args.adb_timeout)


def build_parser():
    """Arguments ; --help sort 0, tout argument inconnu sort 2."""
    parser = argparse.ArgumentParser(
        prog="route-replay.py", add_help=False,
        description="Rejoue un trajet CSV/GPX sur un émulateur Android (adb emu geo fix).")
    parser.add_argument("--route", metavar="FICHIER", help="CSV 'lat,lon' ou GPX")
    parser.add_argument("--adb", metavar="BIN", default="", help="binaire adb")
    parser.add_argument("--serial", metavar="SÉRIE", default="",
                        help="série adb (obligatoire hors --dry-run)")
    parser.add_argument("--pkg", metavar="PAQUET", default="dev.sam3vision.app",
                        help="paquet Android à alimenter (défaut : dev.sam3vision.app)")
    parser.add_argument("--mode", metavar="M", default="auto",
                        choices=("auto", "broadcast", "geofix"),
                        help="canal de position : auto (défaut), broadcast, geofix")
    parser.add_argument("--hz", metavar="N", type=float, default=1.0,
                        help="cadence d'envoi en Hz (défaut 1)")
    parser.add_argument("--max-seconds", metavar="N", type=float, default=0.0,
                        help="durée totale maximale (0 = toute la trace)")
    parser.add_argument("--adb-timeout", metavar="S", type=float, default=10.0,
                        help="timeout d'un geo fix en secondes (défaut 10)")
    parser.add_argument("--loop", action="store_true",
                        help="rejouer la trace en boucle (jusqu'à SIGTERM)")
    parser.add_argument("--dry-run", action="store_true",
                        help="afficher les points sans contacter l'émulateur")
    parser.add_argument("-h", "--help", action="store_true", help="cette aide")
    return parser


def replay(args, adb, points, send, mode):
    """Envoie les points à la cadence, en boucle si demandé. Code de sortie."""
    period = 1.0 / args.hz
    started = time.monotonic()
    failures = 0
    sent = 0
    while True:
        for point in points:
            if args.max_seconds and time.monotonic() - started >= args.max_seconds:
                print("rejeu : %d points envoyés (durée atteinte)" % sent)
                clear(adb, args, mode)
                return PKG_OK
            if send(adb, args.serial, point, args.adb_timeout):
                failures = 0
                sent += 1
            else:
                failures += 1
                if failures >= 2:
                    die("deux envois consécutifs ont échoué — série '%s' en ligne ?"
                        % args.serial)
            time.sleep(period)
        if not args.loop:
            break
    print("rejeu : %d points envoyés en %.1fs" % (sent, time.monotonic() - started))
    clear(adb, args, mode)
    return PKG_OK


def main(argv):
    """Valide les arguments, lit la trace, puis envoie les points à la cadence."""
    parser = build_parser()
    args, unknown = parser.parse_known_args(argv)
    if args.help:
        parser.print_help(sys.stdout)
        print("\nUsage : route-replay.py --route FICHIER [--serial SÉRIE] "
              "[--hz N] [--max-seconds N] [--loop] [--dry-run]")
        return PKG_OK
    if unknown:
        parser.print_usage(sys.stderr)
        print("ERREUR : route-replay.py : argument inconnu : '%s'" % unknown[0],
              file=sys.stderr)
        return PKG_USAGE
    if not args.route:
        parser.print_usage(sys.stderr)
        print("ERREUR : --route est requis (CSV 'lat,lon' ou GPX)", file=sys.stderr)
        return PKG_USAGE
    if args.hz <= 0:
        die("--hz doit être positif", PKG_USAGE)
    points = read_route(args.route)
    print("trajet : %d points (%s)" % (len(points), args.route))
    if args.dry_run:
        for index, (lat, lon) in enumerate(points, 1):
            print("  %3d  %.6f, %.6f" % (index, lat, lon))
        return PKG_OK
    if not args.serial:
        die("--serial est requis pour rejouer (comparez 'adb devices' avant/après le boot)",
            PKG_USAGE)
    adb = resolve_adb(args.adb)
    mode = choose_mode(args.mode, adb, args.serial, args.pkg, args.adb_timeout)
    sender = make_sender(mode, args.pkg)
    return replay(args, adb, points, sender, mode)


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        sys.exit(130)
