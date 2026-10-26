#!/usr/bin/env bash
# Recette : tap sur l'accueil = mode vidéo (auto-démarré) ; appui long = voix (pendant la vidéo).
# Env : EMULATOR_SERIAL (défaut emulator-5554), APK, ADB (voir --help).
set -euo pipefail
export LC_ALL=C.UTF-8 LANG=C.UTF-8
. "$(dirname -- "${BASH_SOURCE[0]}")/lib/common.sh" ||
  { printf 'ERREUR : scripts/lib/common.sh introuvable\n' >&2; exit 1; }

read -r -d '' USAGE <<'EOF' || true
Usage : verify-tap-voix.sh [--help]

Recette manuelle sur émulateur : installe l'APK, revient à l'accueil, puis déroule
les gestes (tap = vidéo, appui long 4 s = voix) en affichant l'écran à chaque étape.
Script de diagnostic : il imprime l'état observé, il ne valide pas par un code de sortie.

Variables d'environnement :
  EMULATOR_SERIAL  série adb de l'émulateur (défaut : emulator-5554)
  APK              chemin de l'APK (défaut : <dépôt>/app/build/outputs/apk/debug/app-debug.apk)
  ADB              binaire adb (défaut : adb du PATH, sinon $ANDROID_HOME/platform-tools/adb)

Aucune valeur secrète n'est lue ni affichée : le logcat est compté, jamais recopié.
EOF

PKG="dev.sam3vision.app"
SERIAL=""
APK_PATH=""
UI_XML=""
REMOTE_UI=""

# call <commande adb shell...> : sortie et erreurs regroupées, délai 40 s.
call() { timeout 40 "$ADB" -s "$SERIAL" shell "$@" 2>&1; }

# dump : uiautomator dump → UI_XML, 3 tentatives (le premier dump échoue souvent).
dump() {
  local _
  for _ in 1 2 3; do
    call "uiautomator dump $REMOTE_UI >/dev/null 2>&1; cat $REMOTE_UI" >"$UI_XML" || :
    grep -q '</hierarchy>' "$UI_XML" && return 0
  done
  return 1
}

# ui : texte des nœuds de l'écran courant, sur une ligne bornée à 240 caractères.
ui() {
  dump || :
  grep -o 'text="[^"]*"' "$UI_XML" | sed 's/text=//' | grep -v '^""$' |
    tr '\n' '|' | cut -c1-240 || :
  echo
}

# vib : ligne d'état du vibreur (vide si aucune information).
vib() {
  call "dumpsys vibrator_manager" | grep -m1 isVibrating | tr -d ' ' || :
}

# install_apk : installation explicite — sans APK valable, la recette ne veut rien dire.
install_apk() {
  local out
  if ! out="$(timeout 240 "$ADB" -s "$SERIAL" install -r "$APK_PATH" 2>&1)"; then
    printf '%s\n' "$out" >&2
    die "échec de l'installation de $APK_PATH sur $SERIAL"
  fi
  printf '%s\n' "$out" | tail -1
}

main() {
  std_args "verify-tap-voix.sh" "$USAGE" "$@"

  resolve_adb
  require_cmd timeout "Installez coreutils (paquet 'coreutils')."
  SERIAL="${EMULATOR_SERIAL:-emulator-5554}"

  APK_PATH="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
  [[ -f "$APK_PATH" ]] ||
    die "APK absent : $APK_PATH (lancez scripts/build.sh ou passez APK=/chemin/app-debug.apk)"

  make_tmpdir
  UI_XML="${TMP_WORK}/tap-ui.xml"
  REMOTE_UI="/sdcard/isee_ui_$$.xml"
  trap cleanup EXIT

  echo "### 0. install"
  install_apk
  call "pm grant $PKG android.permission.CAMERA" || :
  call "pm grant $PKG android.permission.RECORD_AUDIO" || :
  call "am force-stop $PKG" >/dev/null || :
  call "am start -n $PKG/.MainActivity" >/dev/null || warn "am start a échoué."
  sleep 9
  # l'app reprend l'écran où elle était : on revient à l'accueil avant de tester
  local _
  for _ in 1 2 3; do
    dump || :
    grep -q 'Photographier' "$UI_XML" && break
    call "input keyevent KEYCODE_BACK" >/dev/null || :
    sleep 2
  done

  echo "### 1. Accueil : les deux gestes annoncés"
  ui

  echo "### 2. Un tap sur l'aperçu -> mode vidéo + démarrage"
  call "input tap 540 1500" >/dev/null || :
  sleep 6
  echo "  écran : $(ui)"
  echo "  vibreur : $(vib)"

  echo "### 3. Appui long 4 s pendant la vidéo -> voix"
  call "input swipe 540 1500 540 1500 4000" >/dev/null &
  sleep 2
  echo "  pendant : $(ui |
    grep -o "J'ÉCOUTE[^|]*\|Parle[^|]*\|DICTÉE[^|]*\|Recording[^|]*\|RECORDING[^|]*" |
    head -2 | tr '\n' ' ')"
  sleep 3
  echo "  après le lever : $(ui | cut -c1-160)"

  echo "### 4. Second tap : le bandeau de dictée se ferme, puis le tap suivant arrête et envoie"
  call "input tap 540 1500" >/dev/null || : # ferme le bandeau de dictée
  sleep 2
  call "input tap 540 1500" >/dev/null || :
  sleep 4
  echo "  écran : $(ui | cut -c1-200)"
  sleep 8
  echo "  plus tard : $(ui | cut -c1-200)"

  echo "### 5. crash ?"
  echo "  buffer crash : $(call "logcat -d -b crash" | grep -c "$PKG" || :)"
  echo "  pid : $(call "pidof $PKG")"
}

# cleanup : supprime le dump distant et les temporaires locaux, sans jamais échouer.
cleanup() {
  if [[ -n "${REMOTE_UI:-}" && -n "${ADB:-}" && -n "${SERIAL:-}" ]]; then
    timeout 20 "$ADB" -s "$SERIAL" shell rm -f "$REMOTE_UI" >/dev/null 2>&1 || :
  fi
  [[ -n "${TMP_WORK:-}" ]] && safe_rm_rf "$TMP_WORK" || :
}

main "$@"