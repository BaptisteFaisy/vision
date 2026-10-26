#!/usr/bin/env bash
# Helpers communs aux scripts de `scripts/` — destiné à être SOURCÉ, jamais exécuté.
# Contrat :
#   - aucune valeur secrète n'est jamais affichée par ces helpers ;
#   - la racine du dépôt est déduite du chemin de ce fichier, jamais d'un chemin
#     absolu de machine ;
#   - les temporaires passent par mktemp, la suppression par safe_rm_rf (chemin vide
#     ou hors TMPDIR refusé).

# Racine du dépôt : scripts/lib/../.. — un `cd` raté s'arrête tout de suite.
_lib_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)" ||
  { printf 'ERREUR : dossier scripts/lib introuvable\n' >&2; exit 1; }
ROOT="$(cd -- "${_lib_dir}/../.." && pwd)" ||
  { printf 'ERREUR : racine du dépôt introuvable depuis %s\n' "$_lib_dir" >&2; exit 1; }
unset _lib_dir

# die <message> : message d'erreur sur stderr puis code 1.
die() { printf 'ERREUR : %s\n' "$*" >&2; exit 1; }

# warn <message> : avertissement non bloquant.
warn() { printf 'ATTENTION : %s\n' "$*" >&2; }

# require_cmd <commande> [indice d'installation]
require_cmd() {
  local cmd="$1" hint="${2:-}"
  command -v -- "$cmd" >/dev/null 2>&1 && return 0
  if [[ -n "$hint" ]]; then
    die "commande '$cmd' introuvable. $hint"
  fi
  die "commande '$cmd' introuvable (installez-la puis relancez)."
}

# require_uint <nom> <valeur> : la valeur doit être un entier positif (durées).
require_uint() {
  local name="$1" value="${2:-}"
  case "$value" in
    '' | *[!0-9]*) die "$name doit être un entier positif en secondes (reçu : '$value')." ;;
  esac
}

# std_args <nom-du-script> <texte-usage> [arguments...]
# Sans argument : on continue. -h/--help : usage sur stdout, code 0.
# Tout autre argument : code 2, message sur stderr.
std_args() {
  local name="$1" text="$2"
  shift 2
  (( $# == 0 )) && return 0
  case "$1" in
    -h | --help)
      printf '%s\n' "$text"
      exit 0
      ;;
    *)
      printf "ERREUR : %s : argument inconnu : '%s'\n(essayez --help)\n" "$name" "$1" >&2
      exit 2
      ;;
  esac
}

# safe_rm_rf <chemin> : seule suppression récursive autorisée du dépôt.
# Refuse un chemin vide, un chemin contenant '..', ou hors de ${TMPDIR:-/tmp}.
safe_rm_rf() {
  local path="${1:-}" base="${TMPDIR:-/tmp}"
  base="${base%/}"
  [[ -n "$path" ]] || die "safe_rm_rf : chemin vide refusé"
  case "/${path}/" in
    */../*) die "safe_rm_rf : '..' interdit dans '$path'" ;;
  esac
  case "$path" in
    "${base}"/*) ;;
    *) die "safe_rm_rf : '$path' n'est pas sous '${base}' — refusé" ;;
  esac
  rm -rf -- "$path"
}

# make_tmpdir : crée un dossier temporaire privé, exposé dans TMP_WORK.
# Le nettoyage est à la charge du script appelant (trap EXIT + safe_rm_rf),
# pour que plusieurs temporaires puissent cohabiter avec d'autres nettoyages.
TMP_WORK=""
make_tmpdir() {
  [[ -n "$TMP_WORK" ]] && return 0
  TMP_WORK="$(mktemp -d "${TMPDIR:-/tmp}/$(basename -- "$0" .sh).XXXXXX")" ||
    die "mktemp -d a échoué (TMPDIR='${TMPDIR:-/tmp}')"
}

# resolve_adb : renseigne la variable globale ADB.
# Ordre : $ADB fourni par l'appelant, adb du PATH, puis $ANDROID_HOME/platform-tools.
# `ADB` vient de l'environnement : on le conserve tel quel, on ne l'écrase pas.
ADB="${ADB:-}"
resolve_adb() {
  if [[ -n "${ADB:-}" ]]; then
    [[ -x "$ADB" ]] || die "ADB='$ADB' n'est pas un exécutable."
    return 0
  fi
  if command -v -- adb >/dev/null 2>&1; then
    ADB="$(command -v -- adb)"
    return 0
  fi
  local sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
  if [[ -x "${sdk}/platform-tools/adb" ]]; then
    ADB="${sdk}/platform-tools/adb"
    return 0
  fi
  die "adb introuvable : installez les platform-tools du SDK Android et ajoutez \$ANDROID_HOME/platform-tools au PATH (ou passez ADB=/chemin/adb)."
}