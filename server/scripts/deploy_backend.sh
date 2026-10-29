#!/usr/bin/env bash
# Déploiement idempotent du backend sam3-vision sur Modal.
# Le secret `sam3-llm` est poussé depuis l'environnement, jamais en clair (cf. lib-env.sh).
#
#   ./server/scripts/deploy_backend.sh                  # secret + deploy + smoke test
#   ./server/scripts/deploy_backend.sh --skip-smoke     # sans le /health final
#   DRY_RUN=1 ./server/scripts/deploy_backend.sh        # vérifie tout, n'appelle pas Modal
#
# Surchargeables : MODAL_BIN, MODAL_PROFILE, SECRET_NAME, APP_NAME, API_URL, DRY_RUN.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-env.sh
. "$HERE/lib-env.sh"

APP_FILE="$PROJECT_ROOT/backend/modal_app.py"
TEST_CLIENT="$PROJECT_ROOT/scripts/test_api.py"
SKIP_SMOKE=0
DEPLOY_LOG=""

usage() {
  cat <<'TXT'
Usage : deploy_backend.sh [--skip-smoke] [-h|--help]

Déploie backend/modal_app.py sur Modal et met à jour le secret « sam3-llm ».
  --skip-smoke   ne pas lancer le test /health après le déploiement
  -h, --help     cette aide

Variables d'environnement requises : LLM_BASE_URL, LLM_API_KEY, LLM_MODEL
(valeurs jamais affichées ; sans LLM_API_KEY le script refuse de tourner).
Optionnelles : MODAL_BIN, MODAL_PROFILE, SECRET_NAME, APP_NAME, API_URL,
DRY_RUN=1 (valide l'environnement et la présence des fichiers sans appeler Modal).
TXT
}

cleanup() {
  # Fichiers temporaires créés par ce script uniquement (secret en clair : 600).
  cleanup_secret_tmp
  [[ -n "$DEPLOY_LOG" && -f "$DEPLOY_LOG" ]] && rm -f "$DEPLOY_LOG"
  return 0
}
trap cleanup EXIT INT TERM

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-smoke) SKIP_SMOKE=1 ;;
      -h|--help) usage; exit 0 ;;
      *) printf 'ERREUR : option inconnue : %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
    shift
  done
}

preflight() {
  log "Pré-vol"
  if [[ "$DRY_RUN" == "1" ]]; then
    [[ -x "$MODAL_BIN" ]] || warn "CLI Modal absent : $MODAL_BIN (ignoré en DRY_RUN)"
    ok "DRY_RUN=1 : aucun appel Modal ne sera fait"
  else
    [[ -x "$MODAL_BIN" ]] || die "CLI Modal absent : $MODAL_BIN (venv attendu : ~/.venv-modal)"
    local version
    version="$(timeout 30 "$MODAL_BIN" --version 2>/dev/null)" \
      || die "CLI Modal inutilisable : $MODAL_BIN --version"
    ok "modal ${version##* }"
  fi
  command -v python3 >/dev/null || die "python3 introuvable"
  [[ -f "$APP_FILE" ]] || die "backend introuvable : $APP_FILE (attendu : backend/modal_app.py)"
  [[ -f "$TEST_CLIENT" ]] || die "client de test introuvable : $TEST_CLIENT"
  ok "backend : ${APP_FILE#"$PROJECT_ROOT"/}"
}

deploy_app() {
  if [[ "$DRY_RUN" == "1" ]]; then
    log "DRY_RUN : modal deploy ${APP_FILE#"$PROJECT_ROOT"/} --profile $MODAL_PROFILE (non exécuté)"
    return 0
  fi
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
}

dry_run_report() {
  log "DRY_RUN : environnement et fichiers validés"
  printf '  secret « %s » : %s (contrôlé, non poussé)\n' "$SECRET_NAME" "${SECRET_KEYS[*]}"
  printf '  déploiement  : %s --profile %s (non exécuté)\n' \
    "${APP_FILE#"$PROJECT_ROOT"/}" "$MODAL_PROFILE"
  ok "rien n'a été envoyé à Modal — relance sans DRY_RUN pour déployer"
}

main() {
  parse_args "$@"
  log "Variables d'environnement (valeurs jamais affichées)"
  check_env_keys "${SECRET_KEYS[@]}"
  preflight
  sync_secret_from_env
  if [[ "$DRY_RUN" == "1" ]]; then dry_run_report; exit 0; fi
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