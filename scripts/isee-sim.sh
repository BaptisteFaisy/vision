#!/usr/bin/env bash
# Harnais iSee : rejoue une balade enregistrée sur l'émulateur Android, sans téléphone.
# Transcode la vidéo (cache par empreinte), boote avec `-camera-back videofile:…`,
# installe l'APK, ouvre iSee, rejoue le trajet GPS, enregistre l'écran, rapatrie
# vidéo + logcat, puis éteint l'émulateur qu'il a démarré.
# Env : APK, ADB, EMULATOR, FFMPEG, ISEESIM_SIZE, BOOT_TIMEOUT, ISEESIM_CACHE.
set -euo pipefail
export LC_ALL=C.UTF-8 LANG=C.UTF-8
. "$(dirname -- "${BASH_SOURCE[0]}")/lib/common.sh" ||
  { printf 'ERREUR : scripts/lib/common.sh introuvable\n' >&2; exit 1; }

read -r -d '' USAGE <<'EOF' || true
Usage : isee-sim.sh --video <fichier.mp4> [options]

Rejoue une balade déjà enregistrée sur l'émulateur Android, pour qu'un agent
teste ses modifications d'iSee sans téléphone : la vidéo devient le flux de la
caméra arrière, le trajet GPS est réinjecté, l'écran est filmé et les journaux
sont rapatriés.

Options :
  --video <f.mp4>     balade à rejouer (obligatoire, tout format lisible ffmpeg)
  --route <fichier>   trajet GPS à rejouer : CSV `lat,lon` ou GPX
  --seconds N         durée de l'enregistrement d'écran (défaut : 30)
  --record <s.mp4>    fichier d'enregistrement (défaut : sous TMPDIR)
  --avd <nom>         AVD à utiliser, créée si absente (défaut : isee-sim)
  --no-build          réutilise l'APK existant, ne lance jamais Gradle
  --keep              laisse l'émulateur allumé (série affichée)
  --help              cette aide

Variables d'environnement :
  APK, ADB, EMULATOR, FFMPEG   binaires (défauts : PATH puis le SDK Android)
  ISEESIM_SIZE    640x480 (défaut) ou 1280x720
  ISEESIM_CACHE   cache de transcodage (défaut : $XDG_CACHE_HOME/isee-sim)
  BOOT_TIMEOUT    attente maximale du boot en secondes (défaut : 240)

Le harnais ne touche jamais un émulateur qu'il n'a pas démarré : il prend un
port libre, note `adb devices` avant le boot, et n'éteint que la série apparue
entre-temps. Aucune valeur secrète n'est lue ni affichée.
EOF

PKG="dev.sam3vision.app"
ACT="${PKG}/.MainActivity"
SYSIMG="system-images;android-35;google_apis;x86_64"
TAGS="sam3vision iSeeLive iSeeMotion Traverse"

VIDEO="" ROUTE="" RECORD="" AVD="isee-sim"
SECONDS_REC=30
NO_BUILD=0 KEEP=0
ADB="" EMU="" FFMPEG="${FFMPEG:-}" AVDM=""
SERIAL="" PORT="" EMU_PID="" REPLAY_PID=""
APK_PATH="" SIM_VIDEO="" OUT_DIR=""
BOOT_SECONDS=0 BOOT_T0=0
BOOT_TIMEOUT="${BOOT_TIMEOUT:-240}"

# arg_value <option> <valeur-suivante> — exige une valeur après une option.
arg_value() {
  if [[ -z "${2:-}" || "${2:-}" == --* ]]; then
    printf "ERREUR : isee-sim.sh : option %s : valeur manquante\n(essayez --help)\n" "$1" >&2
    exit 2
  fi
}

# parse_args — --help sort 0, tout argument inconnu sort 2.
parse_args() {
  while (( $# > 0 )); do
    local arg="$1"
    shift
    case "$arg" in
      -h | --help) printf '%s\n' "$USAGE"; exit 0 ;;
      --video) arg_value "$arg" "${1:-}"; VIDEO="$1"; shift ;;
      --route) arg_value "$arg" "${1:-}"; ROUTE="$1"; shift ;;
      --seconds) arg_value "$arg" "${1:-}"; SECONDS_REC="$1"; shift ;;
      --record) arg_value "$arg" "${1:-}"; RECORD="$1"; shift ;;
      --avd) arg_value "$arg" "${1:-}"; AVD="$1"; shift ;;
      --no-build) NO_BUILD=1 ;;
      --keep) KEEP=1 ;;
      *)
        printf "ERREUR : isee-sim.sh : argument inconnu : '%s'\n(essayez --help)\n" "$arg" >&2
        exit 2
        ;;
    esac
  done
}

# resolve_tools : valide les entrées puis renseigne ADB, EMU et FFMPEG.
resolve_tools() {
  [[ -f "$VIDEO" ]] || die "vidéo absente : $VIDEO (--video attend un fichier lisible par ffmpeg)"
  [[ -s "$VIDEO" ]] || die "vidéo vide : $VIDEO"
  if [[ -n "$ROUTE" && ! -f "$ROUTE" ]]; then
    die "trajet absent : $ROUTE (--route attend un CSV 'lat,lon' ou un GPX)"
  fi
  resolve_adb
  require_cmd sha256sum "Installez coreutils (paquet 'coreutils')."
  require_cmd timeout "Installez coreutils (paquet 'coreutils')."
  require_cmd python3 "Installez python3 (paquet 'python3')."
  EMU="${EMULATOR:-}"
  if [[ -z "$EMU" ]]; then
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" candidate
    for candidate in "$(command -v emulator 2>/dev/null || :)" \
      "${sdk:+$sdk/emulator/emulator}" "$HOME/Android/Sdk/emulator/emulator" \
      "$HOME/.local/opt/android-sdk/emulator/emulator"; do
      if [[ -n "$candidate" && -x "$candidate" ]]; then
        EMU="$candidate"
        break
      fi
    done
  fi
  [[ -n "$EMU" && -x "$EMU" ]] ||
    die "émulateur introuvable : installez le paquet 'emulator' du SDK Android ou passez EMULATOR=/chemin/emulator"
  FFMPEG="${FFMPEG:-$(command -v ffmpeg 2>/dev/null || :)}"
  if [[ -z "$FFMPEG" && -x "$HOME/.local/bin/ffmpeg" ]]; then FFMPEG="$HOME/.local/bin/ffmpeg"; fi
  [[ -n "$FFMPEG" && -x "$FFMPEG" ]] ||
    die "ffmpeg introuvable : installez-le ou passez FFMPEG=/chemin/ffmpeg"
}

# ensure_apk : renseigne APK_PATH ; construit seulement si --no-build est absent.
ensure_apk() {
  APK_PATH="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
  if [[ ! -f "$APK_PATH" ]]; then
    if (( NO_BUILD )); then
      die "APK absent : $APK_PATH (--no-build : aucune construction ; lancez scripts/build.sh)"
    fi
    echo "→ APK absent, construction (scripts/build.sh)"
    bash "$ROOT/scripts/build.sh" || die "la construction de l'APK a échoué (scripts/build.sh)"
  fi
  [[ -f "$APK_PATH" ]] || die "APK absent après construction : $APK_PATH"
  echo "→ APK : $APK_PATH ($(du -h "$APK_PATH" | cut -f1))"
}

# prepare_video : transcode la vidéo en profil caméra stable, avec cache par
# empreinte (source + profil + version de ffmpeg). Renseigne SIM_VIDEO.
prepare_video() {
  local size="${ISEESIM_SIZE:-640x480}" w h digest key cache_dir part
  case "$size" in
    640x480) w=640 h=480 ;;
    1280x720) w=1280 h=720 ;;
    *) die "ISEESIM_SIZE doit valoir 640x480 ou 1280x720 (reçu : '$size')" ;;
  esac
  digest="$(sha256sum -- "$VIDEO" | awk '{print $1}')"
  key="$(printf '%s|%s|%s' "$digest" "$size" "$("$FFMPEG" -version | head -1)" | sha256sum | cut -c1-16)"
  cache_dir="${ISEESIM_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/isee-sim}"
  mkdir -p -- "$cache_dir" || die "impossible de créer le cache : $cache_dir"
  SIM_VIDEO="${cache_dir}/video-${size}-${key}.mp4"
  if [[ -s "$SIM_VIDEO" ]]; then
    echo "→ cache vidéo : $SIM_VIDEO (empreinte ${key})"
    return 0
  fi
  echo "→ transcodage ${size} H.264 yuv420p, 30 fps, audio retiré (empreinte ${key})"
  part="${SIM_VIDEO}.part-$$"
  # -autorotate applique la rotation déclarée ; -an jette l'audio.
  "$FFMPEG" -hide_banner -nostdin -loglevel error -y -autorotate -i "$VIDEO" \
    -vf "scale=${w}:${h}:force_original_aspect_ratio=decrease,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2,fps=30" \
    -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -profile:v baseline -level 3.1 \
    -g 30 -keyint_min 30 -sc_threshold 0 -fps_mode cfr -an -sn -dn \
    -movflags +faststart -f mp4 "$part" || die "le transcodage ffmpeg a échoué ($VIDEO)"
  [[ -s "$part" ]] || die "transcodage vide : $part"
  mv -f -- "$part" "$SIM_VIDEO"
  echo "→ vidéo normalisée : $SIM_VIDEO ($(du -h "$SIM_VIDEO" | cut -f1))"
}

# sdk_root : racine du SDK, déduite de ANDROID_HOME, de l'émulateur ou d'avdmanager.
# avdmanager ne trouve les images système que si la racine est celle de son SDK ;
# sinon il répond « Package path is not valid ».
sdk_root() {
  if [[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
    printf '%s' "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    return 0
  fi
  local base root
  for base in "$EMU" "$AVDM"; do
    [[ -n "$base" ]] || continue
    root="$(cd -- "$(dirname -- "$base")/.." && pwd -P 2>/dev/null || :)" || :
    if [[ -n "$root" && -d "$root/system-images" ]]; then
      printf '%s' "$root"
      return 0
    fi
  done
  printf '%s' ""
}

# resolve_avdmanager : renseigne AVDM, en priorité celui du SDK de l'émulateur.
resolve_avdmanager() {
  [[ -n "$AVDM" ]] && return 0
  local sdk candidate
  sdk="$(sdk_root)"
  for candidate in "${sdk:+$sdk/cmdline-tools/latest/bin/avdmanager}" \
    "$(command -v avdmanager 2>/dev/null || :)" \
    "$HOME/Android/Sdk/cmdline-tools/latest/bin/avdmanager"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      AVDM="$candidate"
      return 0
    fi
  done
  die "avdmanager introuvable : installez 'cmdline-tools;latest' du SDK ou créez l'AVD à la main"
}

# cfg_set <config.ini> <clé> <valeur> — idempotent, sans doublon.
cfg_set() {
  local ini="$1" key="$2" value="$3" tmp="${1}.tmp-$$"
  awk -v k="$key" -v v="$value" -F' *= *' '
    $1 == k { next }
    { print }
    END { printf "%s = %s\n", k, v }
  ' "$ini" >"$tmp" && mv -f -- "$tmp" "$ini"
}

# ensure_avd : crée l'AVD si absente puis force la configuration attendue.
ensure_avd() {
  local ini="$HOME/.android/avd/${AVD}.avd/config.ini" sdk
  if [[ -f "$ini" ]]; then
    echo "→ AVD réutilisée : $AVD"
  else
    resolve_avdmanager
    sdk="$(sdk_root)"
    [[ -n "$sdk" ]] || die "racine du SDK Android introuvable : exportez ANDROID_HOME"
    [[ -d "$sdk/system-images/android-35/google_apis/x86_64" ]] ||
      die "image système absente : $sdk/system-images/android-35/google_apis/x86_64 (sdkmanager 'system-images;android-35;google_apis;x86_64')"
    echo "→ création de l'AVD $AVD ($SYSIMG)"
    printf 'no\n' | env ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk" \
      timeout 300 "$AVDM" create avd -n "$AVD" -k "$SYSIMG" -d pixel_6 --force >/dev/null ||
      die "avdmanager n'a pas pu créer l'AVD $AVD (image système installée ? sdkmanager --list)"
    [[ -f "$ini" ]] || die "config.ini absente après création de l'AVD : $ini"
  fi
  cfg_set "$ini" hw.ramSize 4096
  cfg_set "$ini" hw.gps yes
  cfg_set "$ini" hw.keyboard yes
  cfg_set "$ini" hw.camera.back emulated
}

# port_busy <port> : vrai si une série adb ou un processus local tient le port.
port_busy() {
  local port="$1"
  if timeout 5 "$ADB" devices 2>/dev/null |
    awk -v s="emulator-$port" '$1 == s { found = 1 } END { exit !found }'; then
    return 0
  fi
  (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null
}

# pick_port : premier port libre de la plage 5554-5600, jamais celui d'un autre.
pick_port() {
  local candidate
  for candidate in $(seq 5554 2 5600); do
    port_busy "$candidate" || { PORT="$candidate"; return 0; }
  done
  die "aucun port libre entre 5554 et 5600 : éteignez un émulateur avant de relancer"
}

# adb_emu <délai> <args adb...> — un seul endroit qui porte la série et le timeout.
adb_emu() {
  local limit="$1"
  shift
  timeout "$limit" "$ADB" -s "$SERIAL" "$@"
}

# boot_emulator : démarre l'émulateur sur PORT avec la vidéo en caméra arrière.
boot_emulator() {
  BOOT_T0="$SECONDS"
  timeout 10 "$ADB" devices 2>/dev/null | awk 'NF > 1 { print $1, $2 }' >"$OUT_DIR/devices-before.txt" || :
  pick_port
  echo "→ boot $AVD sur le port $PORT, caméra arrière = $SIM_VIDEO"
  "$EMU" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-snapshot -no-boot-anim \
    -gpu swiftshader_indirect -camera-back "videofile:$SIM_VIDEO" >"$OUT_DIR/emulator.log" 2>&1 &
  EMU_PID=$!
  SERIAL="emulator-${PORT}"
  wait_serial
  wait_boot
  BOOT_SECONDS=$((SECONDS - BOOT_T0))
}

# emu_alive : vrai si le processus d'émulateur démarré par ce script est vivant.
emu_alive() { kill -0 "$EMU_PID" 2>/dev/null; }
# wait_serial : attend la série dans adb devices, refuse un émulateur préexistant.
wait_serial() {
  local waited=0
  while (( waited < BOOT_TIMEOUT )); do
    if ! emu_alive; then
      tail -20 "$OUT_DIR/emulator.log" >&2 || :
      die "l'émulateur s'est arrêté au démarrage (journal : $OUT_DIR/emulator.log)"
    fi
    if timeout 5 "$ADB" devices 2>/dev/null |
      awk -v s="$SERIAL" '$1 == s && $2 == "device" { found = 1 } END { exit !found }'; then
      if grep -qx "$SERIAL device" "$OUT_DIR/devices-before.txt" 2>/dev/null; then
        die "$SERIAL était déjà connecté avant le boot : arrêt pour ne pas piloter l'émulateur d'un autre"
      fi
      echo "→ série $SERIAL en ligne après $((SECONDS - BOOT_T0))s"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  tail -20 "$OUT_DIR/emulator.log" >&2 || :
  die "pas de série '$SERIAL' dans adb devices après ${BOOT_TIMEOUT}s"
}

# wait_boot : attend sys.boot_completed=1 puis calme les dialogues système.
# hide_error_dialogs supprime les fenêtres ANR/crash qui masqueraient iSee sur
# un boot à froid ; stay_on_while_plugged_in empêche la veille pendant la prise.
wait_boot() {
  local waited=0
  while (( waited < BOOT_TIMEOUT )); do
    if [[ "$(adb_emu 10 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      adb_emu 20 shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || :
      adb_emu 20 shell settings put global stay_on_while_plugged_in 3 >/dev/null 2>&1 || :
      adb_emu 20 shell input keyevent 82 >/dev/null 2>&1 || :
      return 0
    fi
    if ! emu_alive; then
      tail -20 "$OUT_DIR/emulator.log" >&2 || :
      die "l'émulateur s'est arrêté pendant le boot (journal : $OUT_DIR/emulator.log)"
    fi
    sleep 2
    waited=$((waited + 2))
  done
  die "sys.boot_completed n'est jamais passé à 1 sur $SERIAL (${BOOT_TIMEOUT}s)"
}

# install_apk : install -r -d, une version déjà installée ne bloque pas la recette.
install_apk() {
  local out
  echo "→ install -r $PKG sur $SERIAL"
  out="$(adb_emu 300 install -r -d "$APK_PATH" 2>&1)" ||
    die "installation refusée : ${out##*$'\n'} (conflit de signature ? adb -s $SERIAL uninstall $PKG)"
  [[ "$out" == *Success* ]] ||
    die "installation sans 'Success' : $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
}

# grant_perms : permissions de la recette + droit EFFECTIF de la position simulée.
# Sans `appops … mock_location`, le test provider est refusé (tag iSeeSim) : le
# trajet n'arrive jamais au guidage, la caméra n'est pas concernée.
grant_perms() {
  local perm
  for perm in CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION \
    BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
    adb_emu 30 shell pm grant "$PKG" "android.permission.$perm" >/dev/null 2>&1 ||
      warn "pm grant $perm ignoré (déjà accordée ou refusée par l'OS)"
  done
  adb_emu 30 shell appops set "$PKG" android:mock_location allow >/dev/null 2>&1 ||
    warn "appops mock_location refusée : le rejeu GPS restera sans effet"
  adb_emu 20 shell settings put secure location_mode 3 >/dev/null 2>&1 || :
}

# start_app : ouvre l'écran iSee par l'extra adb documenté (--es route isee)
# puis attend qu'il prenne le focus (une fenêtre système peut le retarder).
start_app() {
  adb_emu 30 shell am force-stop "$PKG" >/dev/null 2>&1 || :
  echo "→ am start --es route isee"
  adb_emu 60 shell am start -W -n "$ACT" --es route isee >/dev/null ||
    warn "am start a échoué : l'application n'a peut-être pas démarré"
  local waited=0 focus=""
  while (( waited < 30 )); do
    focus="$(adb_emu 20 shell dumpsys window 2>/dev/null |
      sed -n 's/.*mCurrentFocus=\([^}]*\).*/\1/p' | head -1)"
    [[ "$focus" == *"$PKG"* ]] && return 0
    sleep 2
    waited=$((waited + 2))
  done
  warn "l'application n'a pas pris le focus en 30 s (focus : ${focus:-inconnu})"
}

# wait_frames : attend la première frame convertie par iSee — la preuve que la
# caméra de l'émulateur alimente bien l'analyseur avant de filmer l'écran.
wait_frames() {
  local waited=0
  while (( waited < 60 )); do
    if adb_emu 20 logcat -d -s sam3vision 2>/dev/null | grep -q 'conversion:'; then
      echo "→ caméra reliée : 1re frame convertie après ${waited}s"
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  warn "aucune frame convertie en 60 s (écran iSee ouvert ? caméra liée ?)"
}

# start_route : rejeu GPS en tâche de fond (1 Hz), arrêté par le trap.
start_route() {
  [[ -n "$ROUTE" ]] || return 0
  echo "→ rejeu du trajet $ROUTE à 1 Hz sur $SERIAL"
  python3 -u "$ROOT/scripts/sim/route-replay.py" --route "$ROUTE" --adb "$ADB" \
    --serial "$SERIAL" --hz 1 >>"$OUT_DIR/route-replay.log" 2>&1 &
  REPLAY_PID=$!
}

# record_screen : screenrecord pendant --seconds, puis pull (c'est la preuve).
# Deux tentatives : un émulateur chargé peut rendre « error: closed ».
record_screen() {
  local remote="/sdcard/isee-sim-${PORT}.mp4" attempt
  for attempt in 1 2; do
    echo "→ screenrecord ${SECONDS_REC}s sur $SERIAL (essai $attempt)"
    timeout $((SECONDS_REC + 90)) "$ADB" -s "$SERIAL" shell screenrecord \
      --time-limit "$SECONDS_REC" --bit-rate 8000000 "$remote" ||
      warn "screenrecord a rendu un code non nul (flux peut-être tronqué)"
    if timeout 120 "$ADB" -s "$SERIAL" pull "$remote" "$RECORD" >/dev/null 2>&1 &&
      [[ -s "$RECORD" ]]; then
      adb_emu 30 shell rm -f "$remote" >/dev/null 2>&1 || :
      echo "→ écran enregistré : $RECORD ($(du -h "$RECORD" | cut -f1))"
      return 0
    fi
    emu_alive || break
    warn "enregistrement non rapatrié, nouvelle tentative"
  done
  emu_alive || tail -5 "$OUT_DIR/emulator.log" >&2 || :
  die "enregistrement absent ou vide : $RECORD"
}

# collect_logs : logcat filtré sur les tags iSee + tampon crash, dans OUT_DIR.
collect_logs() {
  adb_emu 60 logcat -d -v threadtime -s $TAGS >"$OUT_DIR/logcat.txt" 2>&1 ||
    warn "logcat filtré indisponible"
  adb_emu 60 logcat -d -v threadtime -b crash >"$OUT_DIR/crash.txt" 2>&1 ||
    warn "tampon crash indisponible"
  adb_emu 30 shell pidof "$PKG" >/dev/null 2>&1 ||
    warn "l'application n'était plus en vie en fin d'enregistrement (voir crash.txt)"
}

# stop_emulator : n'éteint que la série apparue pendant CE boot.
stop_emulator() {
  [[ -n "$SERIAL" && -n "$ADB" ]] || return 0
  if adb_emu 20 emu kill >/dev/null 2>&1; then
    local waited=0
    while (( waited < 60 )); do
      if ! timeout 5 "$ADB" devices 2>/dev/null |
        awk -v s="$SERIAL" '$1 == s { found = 1 } END { exit !found }'; then
        echo "→ émulateur $SERIAL éteint"
        return 0
      fi
      sleep 1
      waited=$((waited + 1))
    done
    warn "$SERIAL répond encore 60 s après 'emu kill' (processus $EMU_PID)"
  else
    warn "adb emu kill a échoué sur $SERIAL"
  fi
}

# cleanup : arrête le rejeu, éteint l'émulateur (sauf --keep), efface TMP_WORK.
cleanup() {
  local rc=$?
  trap - EXIT
  if [[ -n "$REPLAY_PID" ]] && kill -0 "$REPLAY_PID" 2>/dev/null; then
    kill "$REPLAY_PID" 2>/dev/null || :
    wait "$REPLAY_PID" 2>/dev/null || :
  fi
  if (( KEEP == 1 )) && emu_alive; then
    echo "→ émulateur laissé allumé : $SERIAL (adb -s $SERIAL emu kill pour l'éteindre)"
  else
    stop_emulator || :
  fi
  [[ -n "${TMP_WORK:-}" ]] && safe_rm_rf "$TMP_WORK" || :
  exit "$rc"
}

# summary : compte les lignes utiles et rend le verdict (code 0 si la preuve existe).
summary() {
  local tags_line fatals
  tags_line="$(grep -cE ' (sam3vision|iSeeLive|iSeeMotion|Traverse) *:' "$OUT_DIR/logcat.txt" 2>/dev/null || true)"
  fatals="$(grep -cE 'FATAL EXCEPTION|Fatal signal' "$OUT_DIR/crash.txt" 2>/dev/null || true)"
  printf '\nOK : harnais terminé\n'
  printf '  vidéo caméra    : %s (source : %s)\n' "$SIM_VIDEO" "$VIDEO"
  printf '  trajet GPS      : %s\n' "${ROUTE:-aucun}"
  printf '  enregistrement  : %s (%s)\n' "$RECORD" "$(du -h "$RECORD" | cut -f1)"
  printf '  logcat iSee     : %s ligne(s) de tags — %s\n' "${tags_line:-0}" "$OUT_DIR/logcat.txt"
  printf '  crash           : %s ligne(s) fatale(s) — %s\n' "${fatals:-0}" "$OUT_DIR/crash.txt"
  printf '  boot            : %ss, série %s (%s)\n' "$BOOT_SECONDS" "$SERIAL" \
    "$( ((KEEP == 1)) && echo 'laissé allumé' || echo 'éteint')"
  printf '  dossier         : %s\n' "$OUT_DIR"
  [[ "${fatals:-0}" == "0" ]] || warn "$fatals ligne(s) fatale(s) : lisez $OUT_DIR/crash.txt"
}

main() {
  parse_args "$@"
  [[ -n "$VIDEO" ]] || {
    printf "ERREUR : isee-sim.sh : --video est requis (une balade enregistrée)\n(essayez --help)\n" >&2
    exit 2
  }
  require_uint "--seconds" "$SECONDS_REC"
  (( SECONDS_REC > 0 )) || die "--seconds doit être supérieur à 0"
  resolve_tools
  ensure_apk
  make_tmpdir
  OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/isee-sim-out.XXXXXX")" ||
    die "mktemp -d a échoué (TMPDIR='${TMPDIR:-/tmp}')"
  [[ -n "$RECORD" ]] || RECORD="${OUT_DIR}/isee-sim-screen.mp4"
  trap cleanup EXIT
  prepare_video
  ensure_avd
  boot_emulator
  install_apk
  grant_perms
  start_app
  wait_frames
  start_route
  record_screen
  collect_logs
  summary
}

main "$@"
