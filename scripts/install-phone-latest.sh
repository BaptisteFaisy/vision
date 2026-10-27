#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# install-phone-latest.sh — le téléphone de test (2c6852ac) reste sur la
# DERNIÈRE version mergée de GitHub : master de ce dépôt.
#
# Usage : scripts/install-phone-latest.sh [options]
#   (rien)         installe master s'il n'y est pas déjà
#   --check        compare sans rien installer : 0 = à jour, 3 = en retard, 2 = occupé
#   --reinstall    reconstruit et réinstalle même si le SHA est déjà connu
#   --quiet        silencieux sauf erreur (mode cron)
#   --wait S       attente max du verrou téléphone (défaut 0 : ne préempte jamais)
#   --hold 30m     pause : le téléphone ne bouge plus pendant 30 min
#                  (à poser avant de tester une branche de mission)
#   --init         crée le miroir de build et son état
#
# Règles :
#   - ne prend JAMAIS le verrou d'un autre agent (`~/bin/phone`) : téléphone
#     occupé = sortie 2, le prochain tour de cron réessaie ;
#   - construit dans un miroir dédié ($VISION_MIRROR, défaut ~/vision-phone/master),
#     jamais dans l'arbre de travail partagé où les agents écrivent ;
#   - n'installe que du master ; `adb install -r -d` accepte un versionCode plus
#     ancien qu'une build de mission déjà posée (les données de l'app restent).
#
# Cron du poste :   */10 * * * * ~/bin/vision-phone-latest --quiet
# État : ~/.vision-phone/state.env   Log : ~/.vision-phone/install-phone-latest.log
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MIRROR="${VISION_MIRROR:-$HOME/vision-phone/master}"
STATE_DIR="${VISION_PHONE_STATE:-$HOME/.vision-phone}"
STATE="$STATE_DIR/state.env"
PKG="dev.sam3vision.app"
SERIAL="${PHONE_SERIAL:-2c6852ac}"
PHONE="${PHONE_BIN:-$HOME/bin/phone}"
APK="$MIRROR/app/build/outputs/apk/debug/app-debug.apk"
BUILD_TIMEOUT="${VISION_BUILD_TIMEOUT:-900}"

CHECK=0 REINSTALL=0 QUIET=0 WAIT=0 HOLD="" INIT=0
while [ $# -gt 0 ]; do
    case "$1" in
        --check) CHECK=1; shift ;;
        --reinstall) REINSTALL=1; shift ;;
        --quiet) QUIET=1; shift ;;
        --wait)  WAIT="${2:-0}"; shift 2 ;;
        --hold)  HOLD="${2:-30m}"; shift 2 ;;
        --init)  INIT=1; shift ;;
        -h|--help) awk '/^set -euo/{exit} /^#/{sub(/^# ?/, ""); print}' "$0"; exit 0 ;;
        *) echo "install-phone-latest: argument inconnu : $1 (--help)" >&2; exit 2 ;;
    esac
done

mkdir -p "$STATE_DIR"
say() { [ "$QUIET" = 1 ] || echo "$@"; }
log() { printf '%s %s\n' "$(date -Is)" "$*" >> "$STATE_DIR/install-phone-latest.log"; }
die() { echo "install-phone-latest: $*" >&2; log "ERREUR $*"; exit 1; }

# --- miroir de build (une fois) -------------------------------------------
if [ "$INIT" = 1 ]; then
    mkdir -p "$(dirname -- "$MIRROR")"
    timeout 120 git -C "$ROOT" fetch -q origin || die "fetch impossible dans $ROOT"
    if [ -e "$MIRROR/.git" ]; then say "miroir déjà en place : $MIRROR"; exit 0; fi
    timeout 60 git -C "$ROOT" worktree add -q --detach "$MIRROR" origin/master \
        || die "création du miroir impossible ($ROOT → $MIRROR)"
    say "miroir créé : $MIRROR"
    exit 0
fi

# --- pause demandée (test d'une branche de mission) ------------------------
if [ -n "$HOLD" ]; then
    case "$HOLD" in
        *m) secs=$(( ${HOLD%m} * 60 )) ;;
        *h) secs=$(( ${HOLD%h} * 3600 )) ;;
        *s) secs=${HOLD%s} ;;
        *)  secs="$HOLD" ;;
    esac
    echo $(( $(date +%s) + secs )) > "$STATE_DIR/hold"
    say "pause posée : le téléphone ne bougera pas pendant ${HOLD}"
    exit 0
fi
if [ -f "$STATE_DIR/hold" ] && [ "$(cat "$STATE_DIR/hold" 2>/dev/null || echo 0)" -gt "$(date +%s)" ]; then
    log "pause en cours jusqu'à $(date -d "@$(cat "$STATE_DIR/hold")" -Is)"
    say "pause en cours — rien à faire"
    exit 0
fi

# --- ce que dit GitHub -----------------------------------------------------
[ -e "$MIRROR/.git" ] || die "miroir absent : $MIRROR — lancer d'abord : $0 --init"
latest="$(timeout 60 git -C "$MIRROR" ls-remote origin refs/heads/master 2>/dev/null | cut -f1)" || latest=""
[ -n "$latest" ] || { log "GitHub injoignable (ls-remote)"; echo "install-phone-latest: GitHub injoignable" >&2; exit 4; }
short="${latest:0:7}"

if [ -f "$STATE" ]; then
    # shellcheck disable=SC1090
    . "$STATE"                      # INSTALLED_* : journal du dernier passage
fi

# --- version réellement sur le téléphone (sous verrou, court) --------------
phone_version() { # $1 = secondes d'attente ; rend "versionName" ou rien
    "$PHONE" run --wait "$1" --task "vision: lire la version installée" -- \
        adb -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null \
        | sed -n 's/^ *versionName=\(.*\)$/\1/p' | head -1
}

# --- ce que le téléphone porte VRAIMENT -----------------------------------
# On relit la version à chaque tour (verrou court) au lieu de se fier à
# l'état enregistré : un autre agent peut avoir installé sa build entre-temps,
# et c'est justement ce qu'on veut voir et corriger.
if [ "$CHECK" = 1 ]; then
    on_phone="$(phone_version "$WAIT")" || exit 2
    say "GitHub master : ${short}   téléphone : ${on_phone:-absent}"
    case "$on_phone" in
        *"-$short") exit 0 ;;
        *)          exit 3 ;;
    esac
fi

on_phone="$(phone_version "$WAIT")" || { log "téléphone occupé — je repasse plus tard"; exit 2; }
if [ "$REINSTALL" = 0 ] && printf '%s' "$on_phone" | grep -q -- "-$short\$"; then
    say "à jour : ${on_phone} (master $short) — rien à faire"
    exit 0
fi
say "téléphone sur ${on_phone:-rien}, master sur $short → build"

# --- miroir sur master -----------------------------------------------------
timeout 120 git -C "$MIRROR" fetch -q origin || die "fetch impossible dans le miroir"
timeout 60 git -C "$MIRROR" checkout -q --detach origin/master || die "checkout origin/master impossible"
head_sha="$(git -C "$MIRROR" rev-parse HEAD)" || die "rev-parse impossible dans le miroir"
[ "$head_sha" = "$latest" ] || die "miroir sur ${head_sha:0:7}, GitHub sur $short — refus d'installer autre chose"
say "build de $short dans le miroir…"

# --- build (hors verrou : c'est long) --------------------------------------
set +e
( cd "$MIRROR" && GRADLE_TIMEOUT="$BUILD_TIMEOUT" timeout "$BUILD_TIMEOUT" bash scripts/build.sh ) \
    > "$STATE_DIR/build.log" 2>&1
built=$?
set -e
if [ "$built" != 0 ]; then
    tail -5 "$STATE_DIR/build.log" >&2
    die "build en échec (log : $STATE_DIR/build.log)"
fi
[ -f "$APK" ] || die "APK absent après build : $APK"

# --- install + relecture de la version, sous un seul verrou ----------------
# `-d` : accepte un versionCode plus ancien que celui déjà installé (master peut
# être plus vieux qu'une build de mission posée sur le téléphone) ; les données
# de l'app sont conservées, rien n'est désinstallé.
rc=0
out="$("$PHONE" run --wait "$WAIT" --task "vision: install master $short" -- bash -lc \
    "adb -s $SERIAL install -r -d '$APK' && adb -s $SERIAL shell dumpsys package $PKG | sed -n 's/^ *versionName=//p' | head -1" 2>&1)" || rc=$?
if [ "$rc" != 0 ] || ! printf '%s' "$out" | grep -q Success; then
    log "install refusée (rc=$rc) : $(printf '%s' "$out" | tr '\n' ' ' | cut -c1-200)"
    [ "$rc" = 2 ] && exit 2                                     # occupé : on réessaiera
    echo "install-phone-latest: install refusée :" >&2
    printf '%s\n' "$out" | tail -3 >&2
    exit 5
fi
now_ver="$(printf '%s\n' "$out" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+' | tail -1)" || now_ver=""
{
    printf 'INSTALLED_SHA=%s\n' "$latest"
    printf 'INSTALLED_VERSION=%s\n' "${now_ver:-$short}"
    printf 'INSTALLED_AT=%s\n' "$(date -Is)"
} > "$STATE"
say "installé : ${now_ver:-$short} (master $short)"
log "installé ${now_ver:-$short} — master $short"
exit 0