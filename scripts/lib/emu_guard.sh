#!/usr/bin/env bash
# Garde anti-segfault hôte des boots émulateur iSee — SOURCÉ, jamais exécuté.
# Contexte : qemu-system-x86_64-headless segfaulte (signal 11, exit 139) de façon
# intermittente sur `-camera-back videofile:` — 14 SIGSEGV le 2026-11-03, toutes
# vidéos, swiftshader comme swiftshader_indirect (preuves : `coredumpctl list`) ;
# le binaire qemu n'est pas patchable depuis ce dépôt. Cette lib rend le harnais
# honnête et robuste : diagnostic « SEGFAULT HÔTE » distinct du crash
# applicatif, verrou exclusif par AVD (un double boot coïncidait avec un segfault
# le 02/11), caméra et GPU paramétrables, vidéo en cache re-validée avant boot.
# Contrat : aucune valeur secrète affichée ; aucune suppression (voir common.sh).

# emu_lock <avd> : verrou exclusif non bloquant par AVD, tenu (fd 9) jusqu'à la
# fin du processus. Refuse (code 1) si un autre run boote déjà cet AVD.
EMU_LOCK_FILE=""
emu_lock() {
  local avd="$1"
  EMU_LOCK_FILE="${TMPDIR:-/tmp}/isee-sim-${avd}.lock"
  exec 9>"$EMU_LOCK_FILE" || return 1
  if ! flock -n 9 2>/dev/null; then
    printf 'ERREUR : AVD %s déjà en cours de boot par un autre run (verrou : %s)\n' \
      "$avd" "$EMU_LOCK_FILE" >&2
    exec 9>&- || :
    return 1
  fi
  printf '%s' "$$" >&9 || :
  return 0
}

# emu_camera_args <mode> <video> : imprime la valeur de `-camera-back`.
# `none` n'imprime rien (l'appelant omet le flag) ; mode inconnu => code 1.
emu_camera_args() {
  local mode="$1" video="$2"
  case "$mode" in
    videofile) printf 'videofile:%s' "$video" ;;
    virtualscene) printf 'virtualscene' ;;
    webcam0) printf 'webcam0' ;;
    none) return 0 ;;
    *)
      printf 'ERREUR : caméra inconnue : %s (--camera videofile|virtualscene|webcam0|none)\n' \
        "$mode" >&2
      return 1
      ;;
  esac
}

# emu_gpu_flag [mode] : imprime le mode GPU validé (liste blanche).
emu_gpu_flag() {
  local mode="${1:-swiftshader_indirect}"
  case "$mode" in
    swiftshader_indirect | swiftshader | host | angle_indirect | guest | off)
      printf '%s' "$mode"
      ;;
    *)
      printf 'ERREUR : GPU inconnu : %s (--gpu swiftshader_indirect|swiftshader|host|angle_indirect|guest|off)\n' \
        "$mode" >&2
      return 1
      ;;
  esac
}

# emu_probe_video <ffmpeg> <fichier> : vrai si le fichier se décode (vers null).
emu_probe_video() {
  local ffmpeg="$1" file="$2"
  [[ -s "$file" ]] || return 1
  "$ffmpeg" -hide_banner -nostdin -loglevel error -i "$file" -f null - >/dev/null 2>&1
}

# emu_diagnose <pid> <emulator.log> <étape> : le processus est mort (ou mourant) ;
# attend sa fin, récupère le code via wait, imprime le diagnostic. Rend toujours 0
# (l'appelant choisit die/exit/break), pour rester appelable sous `set -e`.
emu_diagnose() {
  local pid="$1" log="$2" step="$3" code=0 waited=0
  while kill -0 "$pid" 2>/dev/null && (( waited < 10 )); do
    sleep 0.1
    waited=$((waited + 1))
  done
  wait "$pid" 2>/dev/null || code=$?
  if [[ "$code" == "139" ]] ||
    tail -5 "$log" 2>/dev/null | grep -qiE 'segmentation fault|signal 11|SIGSEGV'; then
    printf 'ERREUR : étape %s : SEGFAULT HÔTE qemu (exit %s) — pas un crash applicatif.\n' \
      "$step" "$code" >&2
    printf 'Journal : %s — coredumps : `coredumpctl list` puis `coredumpctl info <pid>`.\n' \
      "$log" >&2
    printf 'Contournements : --camera virtualscene|none pour les tests non-live,\n' >&2
    printf 'ISEESIM_GPU=swiftshader, ou rejouez à hôte calme (jamais deux boots du même AVD).\n' >&2
    return 0
  fi
  printf 'ERREUR : étape %s : émulateur arrêté (exit %s, journal : %s)\n' \
    "$step" "$code" "$log" >&2
  tail -20 "$log" >&2 || :
  return 0
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
