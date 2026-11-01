#!/usr/bin/env bash
# latence-ab.sh — campagne A/B binaire-vs-base64, UNE jambe par appel.
# Boote isee-sim via scripts/isee-sim.sh (émulateur, JAMAIS le téléphone),
# mesure N frames ISEE_LAT, rapatrie le dump, vérifie la jambe (ligne `leg …`),
# puis éteint l'émulateur. La comparaison des deux dumps se fait ensuite avec
# scripts/latence-ab-compare.py. Protocole : docs/LATENCE-M1-HARNESS.md.
# Env : ADB, BOOT_TIMEOUT/SIM_TIMEOUT (transmis à isee-sim.sh / garde du boot).
set -euo pipefail
export LC_ALL=C.UTF-8 LANG=C.UTF-8
. "$(dirname -- "${BASH_SOURCE[0]}")/lib/common.sh" ||
  { printf 'ERREUR : scripts/lib/common.sh introuvable\n' >&2; exit 1; }

read -r -d '' USAGE <<'EOF' || true
Usage : latence-ab.sh --leg text|binary --apk <f.apk> --video <f.mp4> [options]

Mesure une jambe du A/B M1 sur l'émulateur isee-sim : boot, install de l'APK de
la jambe, N frames ISEE_LAT, dump, vérification de la jambe, extinction.

Options :
  --leg text|binary   jambe mesurée (obligatoire : texte = base64, binaire = M1)
  --apk <f.apk>       APK de la jambe (obligatoire, construit par build-serveur)
  --video <f.mp4>     balade caméra (obligatoire, la MÊME pour les deux jambes)
  --frames N          frames complètes à collecter (défaut : 520, soit 500
                      mesurées après --skip 20)
  --skip K            warmup retiré à l'analyse (défaut : 20)
  --timeout S         attente max des frames en secondes (défaut : 900)
  --out DIR           dossier des dumps (défaut : /tmp/latence-m1-harness/ART)
  --avd <nom>         AVD à utiliser (défaut : isee-sim)
  --record-seconds N  screenrecord imposé par isee-sim.sh (défaut : 5, hors mesure)
  --help              cette aide

Une jambe = un appel (deux appels pour un A/B) : si la seconde jambe échoue, la
première reste mesurée. L'émulateur est toujours éteint en fin d'appel.
EOF

PKG="dev.sam3vision.app"
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)" || exit 1
SIM="${HERE}/isee-sim.sh"
PARSE="${HERE}/parse_isee_lat.py"

LEG="" APK_PATH="" VIDEO="" AVD="isee-sim" OUT_DIR=""
FRAMES=520 SKIP=20 TIMEOUT=900 RECORD_SECONDS=5
ADB="" SERIAL="" DUMP="" MEASURE_OK=1
SIM_TIMEOUT="${SIM_TIMEOUT:-1500}"

# arg_value <option> <valeur-suivante> — exige une valeur après une option.
arg_value() {
  if [[ -z "${2:-}" || "${2:-}" == --* ]]; then
    printf "ERREUR : latence-ab.sh : option %s : valeur manquante\n(essayez --help)\n" "$1" >&2
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
      --leg) arg_value "$arg" "${1:-}"; LEG="$1"; shift ;;
      --apk) arg_value "$arg" "${1:-}"; APK_PATH="$1"; shift ;;
      --video) arg_value "$arg" "${1:-}"; VIDEO="$1"; shift ;;
      --frames) arg_value "$arg" "${1:-}"; FRAMES="$1"; shift ;;
      --skip) arg_value "$arg" "${1:-}"; SKIP="$1"; shift ;;
      --timeout) arg_value "$arg" "${1:-}"; TIMEOUT="$1"; shift ;;
      --out) arg_value "$arg" "${1:-}"; OUT_DIR="$1"; shift ;;
      --avd) arg_value "$arg" "${1:-}"; AVD="$1"; shift ;;
      --record-seconds) arg_value "$arg" "${1:-}"; RECORD_SECONDS="$1"; shift ;;
      *)
        printf "ERREUR : latence-ab.sh : argument inconnu : '%s'\n(essayez --help)\n" "$arg" >&2
        exit 2
        ;;
    esac
  done
}

# adb_emu <délai> <args adb...> — un seul endroit qui porte la série et le timeout.
adb_emu() {
  local limit="$1"
  shift
  timeout "$limit" "$ADB" -s "$SERIAL" "$@"
}

# precheck : refuse tôt (code 2), avant tout boot.
precheck() {
  [[ -n "$LEG" ]] || { printf 'ERREUR : latence-ab.sh : --leg est requis (text|binary)\n' >&2; exit 2; }
  [[ "$LEG" == text || "$LEG" == binary ]] || {
    printf "ERREUR : latence-ab.sh : --leg vaut 'text' ou 'binary' (reçu : '%s')\n" "$LEG" >&2; exit 2; }
  [[ -n "$APK_PATH" ]] || { printf 'ERREUR : latence-ab.sh : --apk est requis\n' >&2; exit 2; }
  [[ -f "$APK_PATH" ]] || { printf 'ERREUR : latence-ab.sh : APK absent : %s\n' "$APK_PATH" >&2; exit 2; }
  [[ -n "$VIDEO" ]] || { printf 'ERREUR : latence-ab.sh : --video est requis\n' >&2; exit 2; }
  [[ -f "$VIDEO" ]] || { printf 'ERREUR : latence-ab.sh : vidéo absente : %s\n' "$VIDEO" >&2; exit 2; }
  require_uint "--frames" "$FRAMES"
  require_uint "--skip" "$SKIP"
  require_uint "--timeout" "$TIMEOUT"
  require_uint "--record-seconds" "$RECORD_SECONDS"
  (( FRAMES > SKIP )) || die "--frames ($FRAMES) doit dépasser --skip ($SKIP)"
  (( TIMEOUT > 0 && RECORD_SECONDS > 0 )) || die "--timeout et --record-seconds sont > 0"
  [[ -x "$SIM" ]] || die "harnais absent : $SIM"
  require_cmd python3 "Installez python3 (paquet 'python3')."
  require_cmd sha256sum "Installez coreutils (paquet 'coreutils')."
  resolve_adb
  if timeout 10 "$ADB" devices 2>/dev/null | grep -q '^emulator-5554[[:space:]]'; then
    die "port 5554 occupé (emulator-5554 déjà en ligne) : éteignez-le avant la campagne"
  fi
  if [[ -z "$OUT_DIR" ]]; then
    OUT_DIR="/tmp/latence-m1-harness/${LEG}-$(date +%Y%m%d-%H%M%S)"
  fi
  mkdir -p -- "$OUT_DIR" || die "impossible de créer : $OUT_DIR"
  echo "→ jambe $LEG, dumps dans $OUT_DIR"
}

# emu_serials <fichier> — séries émulateur en ligne, une par ligne.
emu_serials() {
  timeout 10 "$ADB" devices 2>/dev/null |
    awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }' | sort >"$1" || :
}

# run_sim : boot + install + iSee via isee-sim.sh (émulateur gardé) ; pose SERIAL.
run_sim() {
  emu_serials "$OUT_DIR/devices-before.txt"
  echo "→ boot isee-sim (APK jambe $LEG)"
  local rc=0
  # shellcheck disable=SC2086
  APK="$APK_PATH" timeout "$SIM_TIMEOUT" bash "$SIM" --video "$VIDEO" \
    --no-build --seconds "$RECORD_SECONDS" --keep --avd "$AVD" \
    >"$OUT_DIR/isee-sim.log" 2>&1 || rc=$?
  emu_serials "$OUT_DIR/devices-after.txt"
  SERIAL="$(comm -13 "$OUT_DIR/devices-before.txt" "$OUT_DIR/devices-after.txt" | head -1)"
  if [[ -z "$SERIAL" ]]; then
    SERIAL="$(grep -o 'laissé allumé : [^ ]*' "$OUT_DIR/isee-sim.log" 2>/dev/null |
      awk '{ print $NF }' | head -1)"
  fi
  (( rc == 0 )) || die "isee-sim.sh a échoué (code $rc, voir $OUT_DIR/isee-sim.log)"
  [[ -n "$SERIAL" ]] || die "aucune série apparue après le boot (voir $OUT_DIR/isee-sim.log)"
  echo "→ série $SERIAL"
}

# emu_gone / app_dead : gardes de la mesure (code 0 = problème).
emu_gone() {
  ! timeout 10 "$ADB" devices 2>/dev/null |
    awk -v s="$SERIAL" '$1 == s && $2 == "device" { found = 1 } END { exit !found }'
}
app_dead() {
  ! adb_emu 20 shell pidof "$PKG" >/dev/null 2>&1
}

# wait_frames : vide le tampon ISEE_LAT puis attend FRAMES lignes `latency N`.
# Rend 1 (sans mourir) si la mesure est incomplète : l'appelant rapatrie le
# partiel avant de conclure — un timeout sans dump ne dit rien.
wait_frames() {
  adb_emu 30 logcat -c >/dev/null 2>&1 || warn "logcat -c refusé : le dump contiendra le boot"
  local waited=0 count=0
  while (( waited < TIMEOUT )); do
    count="$(adb_emu 60 logcat -d -s ISEE_LAT 2>/dev/null | grep -c 'latency [0-9]' || true)"
    count="${count:-0}"
    if (( count >= FRAMES )); then
      echo "→ $count frames ISEE_LAT après ${waited}s"
      return 0
    fi
    if emu_gone; then
      printf 'émulateur %s disparu à %s/%s frames\n' "$SERIAL" "$count" "$FRAMES" >&2
      return 1
    fi
    if app_dead; then
      printf 'application %s morte à %s/%s frames (voir crash.txt)\n' "$PKG" "$count" "$FRAMES" >&2
      return 1
    fi
    sleep 5
    waited=$((waited + 5))
  done
  printf 'seulement %s/%s frames en %ss\n' "$count" "$FRAMES" "$TIMEOUT" >&2
  return 1
}

# collect : dump ISEE_LAT + crash + méta + verdicts (tolérant : un dump partiel
# vaut mieux que rien quand la mesure a été interrompue).
collect() {
  DUMP="$OUT_DIR/isee-lat-$LEG.txt"
  if [[ -n "$SERIAL" ]] && ! emu_gone; then
    adb_emu 120 logcat -d -v threadtime -s ISEE_LAT >"$DUMP" 2>&1 ||
      warn "dump ISEE_LAT incomplet"
    adb_emu 60 logcat -d -v threadtime -b crash >"$OUT_DIR/crash.txt" 2>&1 || :
  else
    warn "émulateur injoignable : pas de dump"
    : >"$DUMP"
  fi
  {
    printf 'leg=%s\n' "$LEG"
    printf 'date=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'apk=%s\n' "$APK_PATH"
    printf 'apk_sha256=%s\n' "$(sha256sum -- "$APK_PATH" | awk '{ print $1 }')"
    printf 'video=%s\n' "$VIDEO"
    printf 'video_sha256=%s\n' "$(sha256sum -- "$VIDEO" | awk '{ print $1 }')"
    printf 'frames=%s\n' "$FRAMES"
    printf 'skip=%s\n' "$SKIP"
    printf 'serial=%s\n' "${SERIAL:-aucune}"
    printf 'avd=%s\n' "$AVD"
  } >"$OUT_DIR/meta.txt"
  if [[ -s "$DUMP" && -f "$PARSE" ]]; then
    python3 "$PARSE" --skip "$SKIP" "$DUMP" >"$OUT_DIR/verdict.txt" 2>&1 || :
    python3 "$PARSE" --skip "$SKIP" --servers "$DUMP" >"$OUT_DIR/servers.txt" 2>&1 || :
    python3 "$PARSE" --skip "$SKIP" --capsplit "$DUMP" >"$OUT_DIR/capsplit.txt" 2>&1 || :
  fi
  echo "→ dump : $DUMP ($(wc -l <"$DUMP" 2>/dev/null || echo 0) lignes)"
}

# check_leg : toutes les lignes `leg …` valent la jambe mesurée — un dump mixte
# (repli texte en pleine jambe binaire) est inutilisable pour le A/B.
check_leg() {
  local got
  got="$(grep -o 'leg [a-z]*' "$DUMP" 2>/dev/null | sort -u | tr '\n' ' ' || true)"
  if [[ -z "$got" ]]; then
    warn "aucune ligne 'leg' dans le dump (APK sans marqueur ?) : jambe attribuée par l'APK ($LEG)"
    return 0
  fi
  [[ "$got" == "leg $LEG " ]] ||
    die "jambe inattendue [$got] (attendu : leg $LEG) — dump inutilisable pour le A/B"
  echo "→ jambe confirmée par le dump : leg $LEG"
}

# stop_serial : éteint l'émulateur de CETTE campagne, jamais un autre.
stop_serial() {
  [[ -n "$SERIAL" ]] || return 0
  emu_gone 2>/dev/null && return 0
  if timeout 20 "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1; then
    local waited=0
    while (( waited < 60 )); do
      emu_gone 2>/dev/null && { echo "→ émulateur $SERIAL éteint"; return 0; }
      sleep 1
      waited=$((waited + 1))
    done
    warn "$SERIAL répond encore 60 s après 'emu kill'"
  else
    warn "adb emu kill a échoué sur $SERIAL"
  fi
}

# cleanup : éteint toujours l'émulateur (pas de --keep : une campagne ne laisse
# rien d'allumé) ; les dumps restent, ce sont le produit de l'appel.
cleanup() {
  local rc=$?
  trap - EXIT
  stop_serial || :
  exit "$rc"
}

# summary : ce que l'appel a produit, et la commande de comparaison.
summary() {
  local frames_measured=$((FRAMES - SKIP))
  printf '\nOK : jambe %s mesurée (%s frames, %s après warmup)\n' "$LEG" "$FRAMES" "$frames_measured"
  printf '  dump     : %s\n' "$DUMP"
  printf '  méta     : %s/meta.txt\n' "$OUT_DIR"
  printf '  verdict  : %s/verdict.txt\n' "$OUT_DIR"
  printf '  comparer : python3 scripts/latence-ab-compare.py --skip %s <dump-text> <dump-binary>\n' "$SKIP"
}

main() {
  parse_args "$@"
  precheck
  trap cleanup EXIT
  run_sim
  wait_frames || MEASURE_OK=0
  collect
  (( MEASURE_OK == 1 )) || die "mesure incomplète (dump partiel : $DUMP)"
  check_leg
  summary
}

main "$@"
