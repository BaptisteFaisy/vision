#!/usr/bin/env bash
# Rotation du secret Modal « sam3-llm » depuis un fichier de clé, puis redéploiement.
#
#   ./scripts/rotate_llm_secret.sh /chemin/cle.txt
#   LLM_KEY_FILE=/chemin/cle.txt ./scripts/rotate_llm_secret.sh
#   DRY_RUN=1 ./scripts/rotate_llm_secret.sh /chemin/cle.txt   # valide sans rien pousser
#
# Le fichier accepte des lignes `CLE=valeur` (api_key|key|token|secret, base_url|
# endpoint, model — avec ou sans préfixe LLM_), des commentaires `#` ou `//`, ou UNE
# SEULE LIGNE BRUTE = la clé API. Variables d'environnement LLM_BASE_URL /
# LLM_API_KEY / LLM_MODEL priment sur le fichier. Idempotent (--force).
# La clé n'est JAMAIS affichée ni loggée : le secret passe par --from-json (lib-env.sh).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-env.sh
. "$HERE/lib-env.sh"
trap cleanup_secret_tmp EXIT INT TERM

# ------------------------------------------------- 1. fichier de clé (entrée)
KEY_FILE="${1:-${LLM_KEY_FILE:-}}"
[[ -n "$KEY_FILE" ]] || die "usage : $0 <fichier-de-cle>  (ou LLM_KEY_FILE=/chemin)"
[[ -r "$KEY_FILE" ]] || die "fichier illisible : $KEY_FILE"
if [[ "$DRY_RUN" != "1" ]]; then
  [[ -x "$MODAL_BIN" ]] || die "CLI Modal absent : $MODAL_BIN"
fi

# ------------------------------------- 2. lecture du fichier (rien n'est affiché)
FILE_KEY="" FILE_URL="" FILE_MODEL=""
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  compact="${line//[[:space:]]/}"
  [[ -z "$compact" || "${compact:0:1}" == "#" || "${compact:0:2}" == "//" ]] && continue
  if [[ "$line" == *=* ]]; then
    key="${line%%=*}"; key="${key//[[:space:]]/}"; key="${key^^}"
    val="${line#*=}"; val="${val#"${val%%[![:space:]]*}"}"; val="${val%"${val##*[![:space:]]}"}"
    val="${val%\"}"; val="${val#\"}"; val="${val%\'}"; val="${val#\'}"
    case "$key" in
      LLM_API_KEY|API_KEY|KEY|TOKEN|SECRET) [[ -n "$FILE_KEY" ]]   || FILE_KEY="$val" ;;
      LLM_BASE_URL|BASE_URL|ENDPOINT)       [[ -n "$FILE_URL" ]]   || FILE_URL="$val" ;;
      LLM_MODEL|MODEL)                      [[ -n "$FILE_MODEL" ]] || FILE_MODEL="$val" ;;
    esac
  elif [[ -z "$FILE_KEY" ]]; then
    FILE_KEY="$compact"                    # ligne brute = la clé
  fi
done < "$KEY_FILE"

LLM_API_KEY="${LLM_API_KEY:-$FILE_KEY}"
LLM_BASE_URL="${LLM_BASE_URL:-${FILE_URL:-$BASE_URL_DEFAULT}}"
LLM_MODEL="${LLM_MODEL:-$FILE_MODEL}"
[[ -n "$LLM_API_KEY" ]] || die "aucune clé API trouvée dans $KEY_FILE"
[[ -n "$LLM_MODEL" ]] || die "LLM_MODEL manquant (dans le fichier ou l'environnement)"
log "clé lue : ${#LLM_API_KEY} caractères (masquée) | modèle : $LLM_MODEL"

# ----------------------- 3. secret Modal : chemin unique de lib-env.sh (600 + JSON)
export LLM_BASE_URL LLM_API_KEY LLM_MODEL
log "secret « $SECRET_NAME » (profil $MODAL_PROFILE, create --force)"
sync_secret_from_env

if [[ "$DRY_RUN" == "1" ]]; then
  log "DRY_RUN=1 : redéploiement ignoré"
  exit 0
fi

# ------------------------------------------------------------- 4. redéploiement
log "redéploiement : scripts/deploy_backend.sh --skip-smoke"
"$PROJECT_ROOT/scripts/deploy_backend.sh" --skip-smoke
log "rotation terminée ; vérifier /ask et /analyze une fois l'app déployée"