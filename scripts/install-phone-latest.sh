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
#   --lock [motif] verrou MANUEL : le téléphone ne bouge plus tant que --unlock
#                  n'a pas été dit (à poser avant de tester une branche de mission)
#   --unlock       lève le verrou (dit qui le tenait)
#   --init         crée le miroir de build et son état
#
# Règles :
#   - ne prend JAMAIS le verrou d'un autre agent (`~/bin/phone`) : téléphone
#     occupé = sortie 2, le prochain tour de cron réessaie ;
#   - construit dans un miroir dédié ($VISION_MIRROR, défaut ~/vision-phone/master),
#     jamais dans l'arbre de travail partagé où les agents écrivent ;
#   - lie le fichier de clés de la machine (VISION_KEYS, défaut
#     ~/.config/sam-vision/build-keys.properties) dans le miroir, sinon l'APK
#     s'installerait sans clé ;
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

CHECK=0 REINSTALL=0 QUIET=0 WAIT=0 LOCK=0 UNLOCK=0 INIT=0 LOCK_MOTIF=""
while [ $# -gt 0 ]; do
    case "$1" in
        --check) CHECK=1; shift ;;
        --reinstall) REINSTALL=1; shift ;;
        --quiet) QUIET=1; shift ;;
        --wait)  WAIT="${2:-0}"; shift 2 ;;
        --lock)   LOCK=1; shift                                  # motif libre après --lock
                  while [ $# -gt 0 ] && [ "${1#-}" = "$1" ]; do LOCK_MOTIF="$LOCK_MOTIF $1"; shift; done ;;
        --unlock) UNLOCK=1; shift ;;
        --init)  INIT=1; shift ;;
        -h|--help) awk '/^set -euo/{exit} /^#/{sub(/^# ?/, ""); print}' "$0"; exit 0 ;;
        *) echo "install-phone-latest: argument inconnu : $1 (--help)" >&2; exit 2 ;;
    esac
done

mkdir -p "$STATE_DIR"
say() { [ "$QUIET" = 1 ] || echo "$@"; }
log() { printf '%s %s\n' "$(date -Is)" "$*" >> "$STATE_DIR/install-phone-latest.log"; }
die() { echo "install-phone-latest: $*" >&2; log "ERREUR $*"; exit 1; }

# keys_build : le miroir est une copie propre, donc sans son `local.properties`
# l'APK part sans aucune clé et la dictée Meta n'a pas de moteur. On **lie** (jamais
# copié, jamais affiché, jamais versionné) le fichier de clés de CETTE machine.
# Absent = on installe quand même, en le disant dans le journal (APK sans clé).
keys_build() {
    local keys="${VISION_KEYS:-$HOME/.config/sam-vision/build-keys.properties}"
    if [ ! -f "$keys" ]; then
        log "aucun fichier de clés ($keys) : APK sans clé"
        return 0
    fi
    ln -sfn "$keys" "$MIRROR/local.properties" || die "lien des clés impossible dans le miroir"
}

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

# --- verrou manuel : je lock tant que j'en ai besoin, je délocke après -------
# Décision user 2026-10-27 : plus de gel programmé (--hold 30m). Un verrou tenu
# jusqu'à ce que quelqu'un le lève, avec qui et pourquoi écrit dedans.
LOCKFILE="$STATE_DIR/lock"
if [ "$LOCK" = 1 ]; then
    {
        printf 'AGENT=%s\n' "${PHONE_AGENT:-$(whoami)@$(hostname -s)}"
        printf 'AT=%s\n' "$(date -Is)"
        printf 'MOTIF=%s\n' "${LOCK_MOTIF# }"
    } > "$LOCKFILE"
    say "verrou posé : le téléphone ne bougera plus jusqu'à --unlock"
    exit 0
fi
lock_field() { sed -n "s/^$1=//p" "$LOCKFILE" 2>/dev/null | head -1; }
if [ "$UNLOCK" = 1 ]; then
    if [ -f "$LOCKFILE" ]; then
        holder="$(lock_field AGENT)"; since="$(lock_field AT)"
        rm -f "$LOCKFILE"
        say "verrou levé (tenu par ${holder:-?} depuis ${since:-?})"
    else
        say "aucun verrou à lever"
    fi
    exit 0
fi
if [ -f "$LOCKFILE" ]; then
    say "verrouillé par $(lock_field AGENT) depuis $(lock_field AT) — rien à faire (--unlock pour lever)"
    log "verrou tenu par $(lock_field AGENT) depuis $(lock_field AT)"
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
keys_build
say "build de $short dans le miroir…"

# --- build (hors verrou : c'est long) --------------------------------------
# Deux essais maximum. Le second sert à un cas vu le 27/10 : le cache KSP du
# miroir se corrompt quand il saute de commit en commit (« Storage corrupted
# …/id-to-file.tab_i », puis « Storage … file-to-id.tab is already registered »
# — le démon Gradle garde l'ancien enregistrement). Sans rattrapage, le cron
# échouait en boucle et le téléphone restait figé sur un vieux master, en
# silence. Le second essai repasse par Gradle directement : `clean` (le démon
# nettoie lui-même, son registre reste cohérent) puis `--no-daemon`, pour ne pas
# tuer le démon des autres agents qui compilent en parallèle.
jdk_home() {  # même ordre que select_jdk de scripts/build.sh, en une ligne
    local c
    for c in "$HOME"/.gradle/jdks/*/; do
        [ -x "${c}bin/javac" ] && { printf '%s' "${c%/}"; return 0; }
    done
    return 1
}

retry_build() {
    local jdk
    jdk="$(jdk_home)" || jdk="${JAVA_HOME:-}"
    ( cd "$MIRROR" && JAVA_HOME="$jdk" timeout "$BUILD_TIMEOUT" \
        ./gradlew --no-daemon clean assembleDebug --offline )
}

set +e
( cd "$MIRROR" && GRADLE_TIMEOUT="$BUILD_TIMEOUT" timeout "$BUILD_TIMEOUT" bash scripts/build.sh ) \
    > "$STATE_DIR/build.log" 2>&1
built=$?
set -e
if [ "$built" != 0 ] && grep -qE 'Storage corrupted|Storage for .* is already registered' "$STATE_DIR/build.log"; then
    say "cache KSP corrompu — nettoyage Gradle, second essai sans démon"
    set +e
    retry_build >> "$STATE_DIR/build.log" 2>&1
    built=$?
    set -e
fi
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