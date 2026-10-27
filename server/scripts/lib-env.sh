#!/usr/bin/env bash
# Helpers partagés par les scripts du dépôt.
# À sourcer : . "$(dirname "${BASH_SOURCE[0]}")/lib-env.sh"
# Fournit : PROJECT_ROOT, log/ok/warn/die, find_jdk & sdk_env (Android),
# et côté Modal : MODAL_BIN, MODAL_PROFILE, SECRET_NAME, APP_NAME, BASE_URL_DEFAULT,
# DRY_RUN, SECRET_KEYS, plus le chemin UNIQUE du secret : sync_secret_from_env.

PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# ------------------------------------------------------------------ console
log()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m  ok\033[0m %s\n' "$*"; }
warn() { printf '\033[33m  !! %s\033[0m\n' "$*"; }
die()  { printf '\033[31mERREUR : %s\033[0m\n' "$*" >&2; exit 1; }

# -------------------------------------------------------------------- Modal
MODAL_BIN="${MODAL_BIN:-$HOME/.venv-modal/bin/modal}"
MODAL_PROFILE="${MODAL_PROFILE:-baptiste-faisy}"
SECRET_NAME="${SECRET_NAME:-sam3-llm}"
APP_NAME="${APP_NAME:-sam3-vision}"
BASE_URL_DEFAULT="${BASE_URL_DEFAULT:-https://openrouter.ai/api/v1}"
DRY_RUN="${DRY_RUN:-0}"
SECRET_KEYS=(LLM_BASE_URL LLM_API_KEY LLM_MODEL)

# Fichier temporaire du secret : supprimé par le trap des scripts appelants.
SECRET_TMP=""
cleanup_secret_tmp() {
  [[ -n "$SECRET_TMP" && -f "$SECRET_TMP" ]] && rm -f "$SECRET_TMP"
  SECRET_TMP=""
  return 0
}

# Vérifie que chaque variable passée est définie et NON VIDE (valeur jamais affichée).
# Un secret vide ne doit jamais atteindre Modal : on refuse avant tout appel.
check_env_keys() {
  local var value missing=0
  for var in "$@"; do
    value="${!var:-}"
    if [[ -n "$value" ]]; then
      ok "$var défini (${#value} caractères)"
    else
      warn "$var manquant ou vide"
      missing=1
    fi
  done
  [[ $missing -eq 0 ]] || die "exporte ${*} (valeurs jamais affichées) puis relance"
}

# Même contrôle, silencieux quand l'appelant vient déjà de l'afficher.
require_keys() {
  local var missing=0
  for var in "$@"; do
    [[ -n "${!var:-}" ]] || { warn "$var manquant ou vide"; missing=1; }
  done
  [[ $missing -eq 0 ]] || die "exporte ${*} (valeurs jamais affichées) puis relance"
}

# Écrit {LLM_BASE_URL, LLM_API_KEY, LLM_MODEL} depuis l'environnement dans un JSON 600.
_secret_json() {
  SECRET_JSON_FILE="$1" python3 - <<'PY'
import json, os
keys = ("LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL")
missing = [k for k in keys if not os.environ.get(k)]
if missing:
    raise SystemExit("variables vides : " + ", ".join(missing))
with open(os.environ["SECRET_JSON_FILE"], "w", encoding="utf-8") as fh:
    json.dump({k: os.environ[k] for k in keys}, fh)
PY
}

# Chemin UNIQUE du secret : mktemp chmod 600 → JSON → `modal secret create --from-json
# (avec écrasement)`. La clé n'est jamais un argument de commande (historique shell, `ps`).
# Refuse de tourner sans LLM_API_KEY ; DRY_RUN=1 valide sans rien créer.
sync_secret_from_env() {
  local rc=0
  require_keys "${SECRET_KEYS[@]}"
  if [[ "$DRY_RUN" == "1" ]]; then
    log "DRY_RUN : modal secret create $SECRET_NAME --from-json <fichier 600> (avec écrasement) (profil $MODAL_PROFILE)"
    return 0
  fi
  SECRET_TMP="$(mktemp "${TMPDIR:-/tmp}/${SECRET_NAME}-secret.XXXXXX.json")" \
    || die "mktemp impossible pour le fichier du secret"
  chmod 600 "$SECRET_TMP"
  _secret_json "$SECRET_TMP" || die "écriture du JSON du secret impossible"
  timeout 120 "$MODAL_BIN" secret create "$SECRET_NAME" --from-json "$SECRET_TMP" \
    --force --profile "$MODAL_PROFILE" >/dev/null || rc=$?
  cleanup_secret_tmp
  [[ $rc -eq 0 ]] || die "création du secret impossible (profil $MODAL_PROFILE, jeton valide ?)"
  ok "secret « $SECRET_NAME » à jour"
}

# -------------------------------------------------------------------- Android
# Cherche un JDK *complet* (avec javac) : le JRE système de cette machine
# (/usr/lib/jvm/java-21-openjdk-amd64) n'a pas de compilateur et casse AGP.
find_jdk() {
  local cand
  # 1. JAVA_HOME déjà valide
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    return 0
  fi
  # 2. JDK provisionnés par Gradle (Adoptium), puis emplacements classiques
  for cand in "$HOME"/.gradle/jdks/eclipse_adoptium-*-amd64-linux*/ \
              "$HOME"/.gradle/jdks/*/ \
              /usr/lib/jvm/*/ \
              /opt/*/; do
    if [ -x "${cand}bin/javac" ]; then
      export JAVA_HOME="${cand%/}"
      export PATH="$JAVA_HOME/bin:$PATH"
      return 0
    fi
  done
  return 1
}

# Positionne ANDROID_HOME / ANDROID_SDK_ROOT / PATH pour le SDK utilisateur.
sdk_env() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
  export PATH="$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:$sdk/emulator:$PATH"
  printf '%s\n' "$sdk"
}