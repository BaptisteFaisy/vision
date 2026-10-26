#!/usr/bin/env bash
# Installe le debug APK sur l'émulateur, accorde CAMERA, lance l'app, screenshot.
# Idempotent : relançable ; reconstruit l'APK seulement s'il manque.
# Env : EMULATOR_SERIAL (défaut emulator-5554), SKIP_BUILD=1 pour ne pas builder,
#       APK et ADB surchargables (voir --help).
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib/common.sh" ||
  { printf 'ERREUR : scripts/lib/common.sh introuvable\n' >&2; exit 1; }

read -r -d '' USAGE <<'EOF' || true
Usage : install-emulator.sh [--help]

Installe l'APK debug sur un émulateur déjà en ligne, accorde CAMERA, lance
l'application, puis enregistre une capture d'écran dont le chemin est affiché à la fin.

Variables d'environnement :
  EMULATOR_SERIAL  série adb de l'émulateur (défaut : emulator-5554)
  SKIP_BUILD=1     ne pas reconstruire l'APK s'il manque (erreur s'il reste absent)
  APK              chemin de l'APK (défaut : <dépôt>/app/build/outputs/apk/debug/app-debug.apk)
  ADB              binaire adb (défaut : adb du PATH, sinon $ANDROID_HOME/platform-tools/adb)
  TMPDIR           dossier des temporaires (défaut : /tmp)

Aucune valeur secrète n'est lue ni affichée par ce script.
EOF

PKG="dev.sam3vision.app"
ACT="${PKG}/.MainActivity"
SERIAL=""
APK_PATH=""
SHOT=""
REMOTE_SHOT=""

# adb_emu <délai> <args adb...> — un seul endroit qui porte la série et le timeout.
adb_emu() {
  local limit="$1"
  shift
  timeout "$limit" "$ADB" -s "$SERIAL" "$@"
}

# cleanup : efface la capture distante et les temporaires locaux, sans jamais échouer.
cleanup() {
  if [[ -n "$REMOTE_SHOT" && -n "${ADB:-}" && -n "$SERIAL" ]]; then
    timeout 20 "$ADB" -s "$SERIAL" shell rm -f "$REMOTE_SHOT" >/dev/null 2>&1 || :
  fi
  [[ -n "${TMP_WORK:-}" ]] && safe_rm_rf "$TMP_WORK" || :
}

# ensure_apk : construit l'APK s'il manque (sauf SKIP_BUILD=1) puis vérifie sa présence.
# Renseigne APK_PATH.
ensure_apk() {
  local apk="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
  if [[ ! -f "$apk" ]]; then
    if [[ "${SKIP_BUILD:-0}" = "1" ]]; then
      die "APK absent : $apk (SKIP_BUILD=1 : pas de reconstruction ; lancez scripts/build.sh)"
    fi
    bash "$ROOT/scripts/build.sh" || die "la construction de l'APK a échoué (scripts/build.sh)"
  fi
  [[ -f "$apk" ]] || die "APK absent après construction : $apk"
  APK_PATH="$apk"
}

# require_device_online : la série doit être à l'état "device" dans adb devices.
require_device_online() {
  if ! timeout 30 "$ADB" devices 2>/dev/null |
    awk -v s="$SERIAL" '$1==s && $2=="device"{found=1} END{exit !found}'; then
    printf "ERREUR : '%s' n'est pas en ligne (adb devices).\n" "$SERIAL" >&2
    timeout 30 "$ADB" devices -l >&2 || :
    exit 1
  fi
}

# capture : capture d'écran sur l'appareil puis rapatriement locale (mktemp).
# Renseigne SHOT et REMOTE_SHOT.
capture() {
  local safe_serial shot_dir
  safe_serial="$(printf '%s' "$SERIAL" | tr -cs 'a-zA-Z0-9' '_')"
  shot_dir="$(mktemp -d "${TMPDIR:-/tmp}/samvision-emu.XXXXXX")" ||
    die "mktemp -d a échoué pour la capture (TMPDIR='${TMPDIR:-/tmp}')"
  SHOT="${shot_dir}/samvision_screen_${safe_serial}.png"
  REMOTE_SHOT="/sdcard/samvision_install_$$.png"

  echo "→ screencap $SHOT"
  adb_emu 60 shell screencap "$REMOTE_SHOT"
  adb_emu 60 pull "$REMOTE_SHOT" "$SHOT" >/dev/null
  [[ -s "$SHOT" ]] || die "capture vide ou absente : $SHOT"
}

main() {
  std_args "install-emulator.sh" "$USAGE" "$@"

  resolve_adb
  require_cmd timeout "Installez coreutils (paquet 'coreutils')."
  SERIAL="${EMULATOR_SERIAL:-emulator-5554}"
  [[ -n "$SERIAL" ]] || die "EMULATOR_SERIAL est vide."

  ensure_apk
  make_tmpdir
  trap cleanup EXIT

  require_device_online

  echo "→ install -r $APK_PATH"
  if ! adb_emu 180 install -r "$APK_PATH"; then
    die "installation refusée. Conflit de signature ? Faire : adb -s $SERIAL uninstall $PKG puis relancer."
  fi

  echo "→ pm grant CAMERA (sans effet si déjà accordée)"
  adb_emu 30 shell pm grant "$PKG" android.permission.CAMERA ||
    echo "  (grant ignoré : permission déjà accordée ou refusée par l'OS)"

  echo "→ force-stop + am start $ACT"
  adb_emu 30 shell am force-stop "$PKG" || :
  adb_emu 30 shell am start -n "$ACT" >/dev/null ||
    warn "am start a échoué : l'application n'a peut-être pas démarré."
  sleep 3 # le temps que Compose se dessine (sous la limite des 30 s)

  capture
  echo "OK : $PKG installé et lancé sur $SERIAL — capture : $SHOT"
}

main "$@"