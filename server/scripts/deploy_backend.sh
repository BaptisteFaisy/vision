#!/usr/bin/env bash
# Déploiement idempotent du backend sam3-vision sur Modal.
# Secret `sam3-llm` créé/actualisé depuis l'environnement (jamais affiché).
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODAL_BIN="${MODAL_BIN:-$HOME/.venv-modal/bin/modal}"
MODAL_PROFILE="${MODAL_PROFILE:-baptiste-faisy}"
SECRET_NAME="${SECRET_NAME:-sam3-llm}"
APP_NAME="${APP_NAME:-sam3-vision}"
APP_FILE="$PROJECT_ROOT/backend/modal_app.py"
TEST_CLIENT="$PROJECT_ROOT/scripts/test_api.py"
SKIP_SMOKE=0
[[ "${1:-}" == "--skip-smoke" ]] && SKIP_SMOKE=1

SECRET_FILE=""
DEPLOY_LOG=""

log()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m  ok\033[0m %s\n' "$*"; }
warn() { printf '\033[33m  !! %s\033[0m\n' "$*"; }
die()  { printf '\033[31mERREUR : %s\033[0m\n' "$*" >&2; exit 1; }

cleanup() {
  # Fichiers temporaires créés par ce script uniquement (secret en clair : 600).
  [[ -n "$SECRET_FILE" && -f "$SECRET_FILE" ]] && rm -f "$SECRET_FILE"
  [[ -n "$DEPLOY_LOG" && -f "$DEPLOY_LOG" ]] && rm -f "$DEPLOY_LOG"
  return 0
}
trap cleanup EXIT INT TERM

preflight() {
  log "Pré-vol"
  [[ -x "$MODAL_BIN" ]] || die "CLI Modal absent : $MODAL_BIN (venv attendu : ~/.venv-modal)"
  local version
  version="$(timeout 30 "$MODAL_BIN" --version 2>/dev/null)" || die "CLI Modal inutilisable : $MODAL_BIN --version"
  ok "modal ${version##* }"
  command -v python3 >/dev/null || die "python3 introuvable"
  [[ -f "$APP_FILE" ]] || die "backend introuvable : $APP_FILE (attendu : backend/modal_app.py)"
  [[ -f "$TEST_CLIENT" ]] || die "client de test introuvable : $TEST_CLIENT"
  ok "backend : ${APP_FILE#"$PROJECT_ROOT"/}"
}

check_env() {
  log "Variables d'environnement (valeurs jamais affichées)"
  local missing=0 var value
  for var in LLM_BASE_URL LLM_API_KEY LLM_MODEL; do
    value="${!var:-}"
    if [[ -n "$value" ]]; then
      ok "$var défini (${#value} caractères)"
    else
      warn "$var manquant"
      missing=1
    fi
  done
  [[ $missing -eq 0 ]] || die "exporte LLM_BASE_URL, LLM_API_KEY et LLM_MODEL puis relance"
}

sync_secret() {
  log "Secret Modal « $SECRET_NAME » (create --force, depuis --from-json)"
  SECRET_FILE="$(mktemp "${TMPDIR:-/tmp}/sam3-secret.XXXXXX.json")"
  chmod 600 "$SECRET_FILE"
  SECRET_FILE="$SECRET_FILE" python3 <<'PY'
import json, os
path = os.environ["SECRET_FILE"]
keys = ("LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL")
with open(path, "w", encoding="utf-8") as fh:
    json.dump({k: os.environ[k] for k in keys}, fh)
PY
  if ! timeout 120 "$MODAL_BIN" secret create "$SECRET_NAME" \
        --from-json "$SECRET_FILE" --force --profile "$MODAL_PROFILE" >/dev/null; then
    die "création du secret impossible (profil $MODAL_PROFILE, jeton valide ?)"
  fi
  rm -f "$SECRET_FILE"; SECRET_FILE=""
  ok "secret à jour"
}

deploy_app() {
  log "Déploiement de ${APP_FILE#"$PROJECT_ROOT"/} (profil $MODAL_PROFILE)"
  DEPLOY_LOG="$(mktemp "${TMPDIR:-/tmp}/sam3-deploy.XXXXXX.log")"
  if ! timeout 900 "$MODAL_BIN" deploy "$APP_FILE" --profile "$MODAL_PROFILE" \
        >"$DEPLOY_LOG" 2>&1; then
    cat "$DEPLOY_LOG" >&2
    die "échec du déploiement (voir la sortie ci-dessus)"
  fi
  cat "$DEPLOY_LOG"
  ok "app déployée"
}

endpoint_url() {
  local url
  url="$(grep -oE 'https://[A-Za-z0-9._-]+\.modal\.run[^[:space:]]*' "$DEPLOY_LOG" \
        | head -n1 | tr -d '",)' || true)"
  if [[ -z "$url" && -n "${API_URL:-}" ]]; then
    url="$API_URL"; warn "URL non trouvée dans les logs, reprise de \$API_URL"
  fi
  printf '%s' "$url"
}

verify_app() {
  local listing="$1"
  log "État de l'app « $APP_NAME »"
  if grep -q "\"$APP_NAME\"" <<<"$listing"; then
    ok "app listée comme déployée"
  else
    warn "« $APP_NAME » absente de modal app list (nom d'app différent ?)"
  fi
}

smoke_test() {
  local url="$1"
  log "Smoke test /health sur $url"
  if ! timeout 90 python3 "$TEST_CLIENT" --url "$url" --only health --timeout 45; then
    die "le endpoint ne répond pas au contrat /health — vérifie les logs : modal app logs $APP_NAME"
  fi
  printf '\n\033[32mDéploiement terminé.\033[0m URL : %s\n' "$url"
  printf 'Test complet : python3 scripts/test_api.py %s\n' "$url"
  printf "App Expo     : ./scripts/dev_qr.sh (puis renseigner l'URL dans Réglages)\n"
}

main() {
  preflight
  check_env
  sync_secret
  deploy_app
  local url listing
  url="$(endpoint_url)"
  listing="$(timeout 60 "$MODAL_BIN" app list --json --profile "$MODAL_PROFILE" 2>/dev/null || echo '[]')"
  verify_app "$listing"
  if [[ -z "$url" ]]; then
    warn "URL du endpoint introuvable automatiquement."
    printf 'Récupère-la avec : %s app list --json  ou dans la console web Modal\n' "$MODAL_BIN"
    exit 3
  fi
  printf '\nURL du endpoint : %s\n' "$url"
  [[ $SKIP_SMOKE -eq 1 ]] && { log "Smoke test ignoré (--skip-smoke)"; return 0; }
  smoke_test "$url"
}

main "$@"