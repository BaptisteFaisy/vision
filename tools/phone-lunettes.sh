#!/usr/bin/env bash
# Usage : tools/phone-lunettes.sh <apk> [--dry] [--no-launch] [--check]
#  Installe et lance le banc d'essai lunettes, écran d'iSee (dev.sam3vision.app,
#  .MainActivity : --ez mock/--ez analyze y poussent Route.Bench), sur le téléphone
#  USB 2c6852ac via le verrou ~/bin/phone (jamais d'adb nu). --dry = diagnostic seul ;
#  --no-launch = installe sans lancer ; --check = compare l'installé à origin/master
#  sans rien installer (0 = à jour, 3 = en retard). Garde SHA : après install, la
#  version posée doit être HEAD master, sinon refus de lancer (build périmée).
#  Preuve : ~/preuves/lunettes/. Code 0 = OK, 1 = échec.
set -euo pipefail
PKG="dev.sam3vision.app"; ACT="$PKG/.MainActivity"
SERIAL="${PHONE_SERIAL:-2c6852ac}"; PHONE="$HOME/bin/phone"; PROOF_ROOT="${PROOF_ROOT:-$HOME/preuves/lunettes}"
ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
APK=""; DRY=0; LAUNCH=1; CHECK=0; WAIT=300; ADB=""; P_LISTED=""; P_PATHS=""; P_DUMP=""

fail() { printf 'ECHEC : %s\n' "$1" >&2; exit 1; }
while [ $# -gt 0 ]; do case "$1" in
  --dry) DRY=1; WAIT=60 ;;
  --no-launch) LAUNCH=0 ;;
  --check) CHECK=1 ;;
  -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  -*) fail "option inconnue : $1" ;;
  *) [ -z "$APK" ] || fail "un seul APK attendu"; APK="$1" ;;
esac; shift; done

# adb résolu comme ailleurs dans le dépôt : $ADB imposé, sinon le SDK, sinon le PATH.
resolve_adb() {
  local c
  for c in "${ADB:-}" "$HOME/android-sdk/platform-tools/adb" \
           "${ANDROID_HOME:-}/platform-tools/adb" "$(command -v adb || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { ADB="$c"; return 0; }
  done
  fail "adb introuvable (ni \$HOME/android-sdk/platform-tools/adb ni adb dans le PATH)"
}
# ph "libellé" <args adb…> — seul point de passage du téléphone : le verrou.
ph() { local label="$1"; shift
  timeout $((WAIT + 120)) "$PHONE" run --wait "$WAIT" --task "lunettes: $label" \
    -- "$ADB" -s "$SERIAL" "$@"
}
# diag : un seul appel adb (une seule prise de verrou), découpé en 3 sections.
diag() { ph "diagnostic" shell "echo '--PAQUETS--'; pm list packages | grep sam3vision; \
echo '--PATH--'; pm path $PKG; echo '--DUMP--'; \
dumpsys package $PKG | grep -E 'versionName|versionCode|codePath'; true" 2>/dev/null || true; }

read_diag() {
  local out; out="$(diag)"
  P_LISTED="$(sed -n '/--PAQUETS--/,/--PATH--/p' <<<"$out" | grep -c sam3vision || true)"
  P_PATHS="$(sed -n '/--PATH--/,/--DUMP--/p' <<<"$out" | grep 'package:' || true)"
  P_DUMP="$(sed -n '/--DUMP--/,$p' <<<"$out" | grep -E 'versionName|versionCode|codePath' || true)"
  printf '%s\n' "$out"
}
# Paquet FANTÔME : `pm list packages` le liste encore alors que `pm path` ne rend rien et
# que /data/app/~~…/dev.sam3vision.app-… a disparu (install interrompue, nettoyage ART) :
# `adb install -r` ne remplace rien et `am start` répond « Activity class does not exist ».
# Purger (uninstall, puis --user 0) ; ici le paquet est iSee : toute l'app est cassée.
is_ghost() { [ "${P_LISTED:-0}" -gt 0 ] && [ -z "$P_PATHS" ]; }

purge() {
  local step
  echo "→ état : paquet FANTÔME (listé, pm path vide) — purge avant réinstallation"
  for step in "pm uninstall $PKG" "pm uninstall --user 0 $PKG"; do  # 2e : trace par utilisateur
    is_ghost || break
    ph "désinstallation" shell $step || true
    read_diag >/dev/null
  done
  is_ghost && fail "le paquet fantôme résiste à pm uninstall (à désinstaller à la main)"
  return 0
}

do_install() {
  local out
  echo "→ installation de $APK"
  out="$(ph "installation APK" install -r "$APK" 2>&1 || true)"
  printf '%s\n' "$out"
  if grep -q INSTALL_FAILED_VERSION_DOWNGRADE <<<"$out"; then
    # Piège du versionCode : un build du r630 peut être numéroté plus bas que la
    # version déjà posée → adb refuse. -d autorise la descente, assumée ici.
    echo "→ INSTALL_FAILED_VERSION_DOWNGRADE : reprise avec -d (descente de versionCode assumée)"
    out="$(ph "installation APK (-d)" install -r -d "$APK" 2>&1 || true)"
    printf '%s\n' "$out"
  fi
  if grep -q INSTALL_FAILED_USER_RESTRICTED <<<"$out"; then
    # MIUI : « Installer via USB » désactivé → le chemin `adb install` (streamed) est
    # refusé, mais le service de paquets accepte une installation LOCALE. Mesuré le
    # 26/10 : `install -r` → USER_RESTRICTED, `push` + `pm install -r` → Success.
    echo "→ INSTALL_FAILED_USER_RESTRICTED (MIUI) : reprise par push + pm install"
    local tmp="/data/local/tmp/$(basename "$APK")"
    out="$(ph "envoi de l'APK" push "$APK" "$tmp" 2>&1 || true)"
    printf '%s\n' "$out"
    out="$(ph "installation APK (pm install)" shell pm install -r "$tmp" 2>&1 || true)"
    printf '%s\n' "$out"
  fi
  grep -qE 'Success|streamed install' <<<"$out" || fail "adb install a échoué : $(tail -n 1 <<<"$out")"
  read_diag >/dev/null
  [ -n "$P_PATHS" ] || fail "pm path resté vide après installation (paquet toujours fantôme)"
  echo "→ installé : $(grep -m1 codePath <<<"$P_DUMP") $(grep -m1 versionName <<<"$P_DUMP")"
}
pid_alive() { ph "vérif process" shell pidof "$PKG" 2>/dev/null | grep -q '[0-9]'; }

do_launch() {
  echo "→ am start $ACT (mock/analyze)"
  ph "lancement activité" shell am start -n "$ACT" --ez mock true --ez analyze true || true
  local fin=$(( $(date +%s) + 60 ))
  until pid_alive || [ "$(date +%s)" -ge "$fin" ]; do sleep 5; done
  pid_alive || fail "process $PKG toujours absent 60 s après am start"
}

do_proof() {
  local dir; dir="$PROOF_ROOT/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$dir"
  ph "logcat" logcat -d -s GlassesSpike:V | tail -n 80 > "$dir/logcat.txt" || true
  ph "version installée" shell dumpsys package "$PKG" 2>/dev/null |
    grep -E 'versionName|versionCode|codePath' > "$dir/version.txt" || true
  ph "capture écran" exec-out screencap -p > "$dir/ecran.png" || true
  echo "Preuve : $dir"
  echo "--- logcat (frame / MockDeviceKit / session / analyse) ---"
  grep -Ei 'frame|MockDeviceKit|session|analyse' "$dir/logcat.txt" | tail -n 15 || true
  [ -s "$dir/ecran.png" ] || echo "(capture écran vide)"
}

# SHA court de master distant (miroir d'install-phone-latest.sh --check : une
# campagne lunettes ne teste que HEAD, jamais une build périmée).
master_short() {
  local sha=""
  sha="$(timeout 60 git -C "$ROOT" ls-remote origin refs/heads/master 2>/dev/null | cut -f1)" || sha=""
  if [ -z "$sha" ]; then  # un DNS qui hoquette ne doit pas coûter la campagne
    sleep 5
    sha="$(timeout 60 git -C "$ROOT" ls-remote origin refs/heads/master 2>/dev/null | cut -f1)" || sha=""
  fi
  [ -n "$sha" ] || fail "GitHub injoignable (ls-remote origin master)"
  printf '%s' "${sha:0:7}"
}
# Version réellement posée sur le téléphone (diagnostic déjà lu).
installed_version() { grep -m1 versionName <<<"$P_DUMP" | sed -n 's/^ *versionName=//p'; }
# --check : compare sans rien installer (codes miroir : 0 = à jour, 3 = en retard).
do_check() {
  local short="$1" on_phone
  on_phone="$(installed_version)"
  echo "GitHub master : $short   téléphone : ${on_phone:-absent}"
  case "$on_phone" in
    *-"$short") echo "→ à jour : ${on_phone} (master $short) — la campagne peut partir"; exit 0 ;;
    *) echo "→ en retard : ${on_phone:-rien} (master $short) — rebuild depuis origin/master" >&2; exit 3 ;;
  esac
}
# Garde SHA : après installation, la version posée doit être HEAD master —
# sinon on refuse de lancer (une build périmée fausserait la campagne).
guard_master_sha() {
  local short on_phone
  short="$(master_short)"
  on_phone="$(installed_version)"
  case "$on_phone" in
    *-"$short") echo "→ SHA OK : ${on_phone} (master $short)" ;;
    *) fail "build périmée : téléphone sur ${on_phone:-rien}, master sur $short — rebuild depuis origin/master avant la campagne" ;;
  esac
}

resolve_adb
ph "état appareils" devices | grep -q "^$SERIAL" ||
  fail "téléphone $SERIAL absent ou non autorisé (adb devices)"
read_diag                                    # diagnostic, en lecture seule
[ "$CHECK" = 1 ] && do_check "$(master_short)"
is_ghost || echo "→ état : paquet normal ($(grep -m1 versionName <<<"$P_DUMP" | tr -s " " | sed "s/^ //"))"

if [ "$DRY" = 1 ]; then
  [ -n "$P_PATHS" ] || echo "(--dry : le paquet fantôme reste en l'état ; relance sans --dry pour purger)"
  exit 0
fi
[ -n "$APK" ] || fail "chemin d'APK manquant : tools/phone-lunettes.sh <apk> [--dry] [--no-launch]"
[ -f "$APK" ] || fail "APK introuvable : $APK"
is_ghost && purge

do_install
guard_master_sha
[ "$LAUNCH" = 1 ] || { echo "→ --no-launch : installé, non lancé"; exit 0; }
do_launch
do_proof
echo "OK — $PKG tourne"
