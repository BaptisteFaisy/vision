#!/usr/bin/env bash
# Recette du port appui long : vibration continue pendant la tenue, queue de 200 ms au
# relâchement, WAV de dictée rangé à l'arrêt, exclusions système respectées, et le tap
# court toujours intact. Env : EMULATOR_SERIAL (défaut emulator-5554), APK, ADB (--help).
set -euo pipefail
export LC_ALL=C.UTF-8 LANG=C.UTF-8
. "$(dirname -- "${BASH_SOURCE[0]}")/lib/common.sh" ||
  { printf 'ERREUR : scripts/lib/common.sh introuvable\n' >&2; exit 1; }

read -r -d '' USAGE <<'EOF' || true
Usage : verify-longpress-port.sh [--help]

Recette manuelle sur émulateur ou téléphone : installe l'APK, revient à iSee, puis
déroule l'appui long et ses voisins (barres système, bords, tap court) en imprimant
l'état observé — vibreur, écran, WAV de dictée rangés, crash. Script de diagnostic :
il raconte ce qu'il voit, il ne valide pas par un code de sortie.

Variables d'environnement :
  EMULATOR_SERIAL  série adb de l'appareil (défaut : emulator-5554)
  APK              chemin de l'APK (défaut : <dépôt>/app/build/outputs/apk/debug/app-debug.apk)
  ADB              binaire adb (défaut : adb du PATH, sinon $ANDROID_HOME/platform-tools/adb)
  HOLD_MOVE_PX     déplacement du doigt pendant l'appui long (défaut : 0). Certains
                   appareils (MIUI) tronquent un `input swipe` sans déplacement en tap
                   d'environ 40 ms : 1 à 23 px suffisent, au-delà de 24 px (~8 dp) la
                   tenue est annulée par le seuil de glissement de l'app.
  WAV_DIR          dossier des dictées rangées (défaut : cache/dictation)

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

# ui : texte des nœuds de l'écran courant, sur une ligne bornée à 160 caractères.
ui() {
  dump || :
  grep -o 'text="[^"]*"' "$UI_XML" | sed 's/text=//' | grep -v '^""$' |
    tr '\n' '|' | cut -c1-160 || :
  echo
}

# vib : ligne d'état du vibreur (vide si le service n'en dit rien).
vib() { call "dumpsys vibrator_manager" | grep -m1 isVibrating | tr -d ' ' || :; }

# wavs : nombre de dictées rangées (le magasin ne garde que les 5 dernières : c'est le
# NOM du plus récent qui prouve une dictée, pas un compteur qui plafonne).
wav_count() { call "run-as $PKG ls $WAV_DIR" 2>/dev/null | grep -c '\.wav' || :; }
wav_latest() { call "run-as $PKG ls -1 $WAV_DIR" 2>/dev/null | tail -1 | tr -d '\r' || :; }
wav_header() {
  local name
  name="$(wav_latest)"
  [[ -n "$name" ]] || { echo "aucun WAV rangé dans $WAV_DIR"; return 0; }
  call "run-as $PKG sh -c 'head -c 12 $WAV_DIR/$name'" | tr -d '\0' || :
  echo " (attendu : RIFF……WAVE, PCM 16 bits mono 16 kHz)"
}

# press <x> <y> <durée ms> <étiquette> : appui long réel, vibreur lu PENDANT la tenue
# (le dump est lent : on lit l'état avant), puis état final.
press() {
  local x="$1" y="$2" ms="$3" label="$4" before
  before="$(wav_latest)"
  (call "input swipe $x $y $x $((y + HOLD_MOVE_PX)) $ms" >/dev/null &)
  sleep 1.2
  echo "  $label | pendant : vibreur=$(vib) | écran=$(ui | cut -c1-90)"
  sleep 2
  echo "  $label | après : vibreur=$(vib) | WAV ${before:-aucun} -> $(wav_latest)"
}

install_apk() {
  local out
  if ! out="$(timeout 240 "$ADB" -s "$SERIAL" install -r "$APK_PATH" 2>&1)"; then
    printf '%s\n' "$out" >&2
    die "échec de l'installation de $APK_PATH sur $SERIAL"
  fi
  printf '%s\n' "$out" | tail -1
}

# back_to_isee : l'app reprend l'écran où elle était ; on revient à iSee avant de tester.
back_to_isee() {
  local _
  for _ in 1 2 3; do
    dump || :
    grep -q 'Photographier\|Dicter\|Pose' "$UI_XML" && return 0
    call "input keyevent KEYCODE_BACK" >/dev/null || :
    sleep 2
  done
  return 0
}

main() {
  local height
  std_args "verify-longpress-port.sh" "$USAGE" "$@"

  resolve_adb
  require_cmd timeout "Installez coreutils (paquet 'coreutils')."
  SERIAL="${EMULATOR_SERIAL:-emulator-5554}"
  HOLD_MOVE_PX="${HOLD_MOVE_PX:-0}"
  WAV_DIR="${WAV_DIR:-cache/dictation}"

  APK_PATH="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
  [[ -f "$APK_PATH" ]] ||
    die "APK absent : $APK_PATH (lancez scripts/build.sh ou passez APK=/chemin/app-debug.apk)"

  make_tmpdir
  UI_XML="${TMP_WORK}/port-ui.xml"
  REMOTE_UI="/sdcard/isee_port_$$.xml"
  trap cleanup EXIT

  echo "### 0. install"
  install_apk
  call "pm grant $PKG android.permission.CAMERA" || :
  call "pm grant $PKG android.permission.RECORD_AUDIO" || :
  call "am force-stop $PKG" >/dev/null || :
  call "am start -n $PKG/.MainActivity" >/dev/null || warn "am start a échoué."
  sleep 9
  back_to_isee
  height="$(call "wm size" | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -d x -f 2 || :)"
  [[ -n "$height" ]] || height=2400

  echo "### 1. Repos : aucune vibration, écran d'accueil"
  echo "  vibreur : $(vib) | WAV rangés : $(wav_count) | écran : $(ui | cut -c1-120)"

  echo "### 2. Appui long 3 s au centre : nappe pendant la tenue, queue de 200 ms après"
  press 540 900 3000 "appui long"
  echo "  écran : $(ui | cut -c1-160)"

  echo "### 3. Tap court : rien (le tap vidéo d'iSee n'ouvre pas la dictée)"
  call "input tap 300 1300" >/dev/null || :
  sleep 2
  echo "  vibreur : $(vib) | écran : $(ui | cut -c1-120)"

  echo "### 4. Appui long dans la barre d'état (haut) : rien"
  press 540 10 3000 "barre d'état  [hors barre : c'est la barre système qui répond]"

  echo "### 5. Appui long dans la barre de navigation (bas) : rien"
  press 540 $((height - 15)) 3000 "barre de navigation"

  echo "### 6. Appui long sur le bord gauche : rien (exclusion du geste retour)"
  press 10 1200 3000 "bord gauche"

  echo "### 7. WAV de dictée rangés (cache/dictation, 5 dernières gardées)"
  echo "  fichiers : $(wav_count) | dernier : $(wav_latest)"
  echo "  en-tête du dernier : $(wav_header)"

  echo "### 8. crash de l'app ?"
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