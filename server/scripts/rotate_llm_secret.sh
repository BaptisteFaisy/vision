#!/usr/bin/env bash
# Rotation du secret Modal « sam3-llm » depuis un fichier de clé, puis redéploiement.
#
#   ./scripts/rotate_llm_secret.sh /chemin/cle.txt
#   LLM_KEY_FILE=/chemin/cle.txt ./scripts/rotate_llm_secret.sh
#
# Le fichier accepte des lignes `CLE=valeur` (api_key|key|token|secret, base_url|
# endpoint, model — avec ou sans préfixe LLM_), des commentaires `#` ou `//`, ou UNE
# SEULE LIGNE BRUTE = la clé API. Variables d'environnement LLM_BASE_URL /
# LLM_API_KEY / LLM_MODEL priment sur le fichier. Idempotent (--force).
# La clé n'est JAMAIS affichée ni loggée.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODAL_BIN="${MODAL_BIN:-$HOME/.venv-modal/bin/modal}"
MODAL_PROFILE="${MODAL_PROFILE:-baptiste-faisy}"
SECRET_NAME="${SECRET_NAME:-sam3-llm}"
BASE_URL_DEFAULT="https://openrouter.ai/api/v1"

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31mERREUR : %s\033[0m\n' "$*" >&2; exit 1; }

SECRET_FILE=""
cleanup() { [[ -n "$SECRET_FILE" && -f "$SECRET_FILE" ]] && rm -f "$SECRET_FILE"; return 0; }
trap cleanup EXIT INT TERM

# --------------------------------------------------------------- 1. entrée
KEY_FILE="${1:-${LLM_KEY_FILE:-}}"
[[ -n "$KEY_FILE" ]] || die "usage : $0 <fichier-de-cle>  (ou LLM_KEY_FILE=/chemin)"
[[ -r "$KEY_FILE" ]] || die "fichier illisible : $KEY_FILE"
[[ -x "$MODAL_BIN" ]] || die "CLI Modal absent : $MODAL_BIN"

# ------------------------------------------- 2. lecture (aucune valeur affichée)
env_key="${LLM_API_KEY:-}" env_url="${LLM_BASE_URL:-}" env_model="${LLM_MODEL:-}"
f_key="" f_url="" f_model=""
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  compact="${line//[[:space:]]/}"
  [[ -z "$compact" || "${compact:0:1}" == "#" || "${compact:0:2}" == "//" ]] && continue
  if [[ "$line" == *=* ]]; then
    key="${line%%=*}"; key="${key//[[:space:]]/}"; key="${key^^}"
    val="${line#*=}"; val="${val#"${val%%[![:space:]]*}"}"; val="${val%"${val##*[![:space:]]}"}"
    val="${val%\"}"; val="${val#\"}"; val="${val%\'}"; val="${val#\'}"
    case "$key" in
      LLM_API_KEY|API_KEY|KEY|TOKEN|SECRET) [[ -n "$f_key" ]] || f_key="$val" ;;
      LLM_BASE_URL|BASE_URL|ENDPOINT)       [[ -n "$f_url" ]] || f_url="$val" ;;
      LLM_MODEL|MODEL)                      [[ -n "$f_model" ]] || f_model="$val" ;;
    esac
  elif [[ -z "$f_key" ]]; then
    f_key="$compact"                       # ligne brute = la clé
  fi
done < "$KEY_FILE"

LLM_API_KEY="${env_key:-$f_key}"
LLM_BASE_URL="${env_url:-${f_url:-$BASE_URL_DEFAULT}}"
LLM_MODEL="${env_model:-$f_model}"
[[ -n "$LLM_API_KEY" ]] || die "aucune clé API trouvée dans $KEY_FILE"
[[ -n "$LLM_MODEL" ]] || die "LLM_MODEL manquant (dans le fichier ou l'environnement)"
log "clé lue : ${#LLM_API_KEY} caractères (masquée) | modèle : $LLM_MODEL"

# ------------------------------- 3. secret Modal (JSON chmod 600, jamais en clair)
export LLM_BASE_URL LLM_API_KEY LLM_MODEL
SECRET_FILE="$(mktemp "${TMPDIR:-/tmp}/sam3-secret.XXXXXX.json")"; chmod 600 "$SECRET_FILE"
SECRET_FILE="$SECRET_FILE" python3 - <<'PY'
import json, os
with open(os.environ["SECRET_FILE"], "w", encoding="utf-8") as fh:
    json.dump({k: os.environ[k] for k in ("LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL")}, fh)
PY
log "secret « $SECRET_NAME » (profil $MODAL_PROFILE, create --force)"
timeout 120 "$MODAL_BIN" secret create "$SECRET_NAME" \
  --from-json "$SECRET_FILE" --force --profile "$MODAL_PROFILE" >/dev/null \
  || die "création du secret impossible (profil $MODAL_PROFILE, jeton valide ?)"
rm -f "$SECRET_FILE"; SECRET_FILE=""

# ------------------------------------------------------------- 4. redéploiement
log "redéploiement : scripts/deploy_backend.sh --skip-smoke"
"$ROOT/scripts/deploy_backend.sh" --skip-smoke
log "rotation terminée ; vérifier /ask et /analyze une fois l'app déployée"
