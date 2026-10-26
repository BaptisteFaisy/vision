#!/usr/bin/env bash
# SEULE commande de déploiement du direct (téléphone) — app sam3-eu-a100, route /live.
#
#   bash scripts/deploy-live-fast.sh              # déploie puis vérifie /health
#   bash scripts/deploy-live-fast.sh --no-deploy  # vérifie seulement (pas de GPU allumée)
#
# Ne JAMAIS déployer backend/live_app.py (version lente, 205-241 ms) : ce script pose les
# deux leviers mesurés (FAST_BATCH_PROMPTS=1, FAST_EDGE=512) → 83-125 ms serveur.
# FAST_TIMING=1 publie les postes par étage (timings_ms + en-tête Server-Timing) et
# FAST_SCALEDOWN=120 garde la carte chaude 2 min : la pause coûtait 23,6 s de réveil.
# La vérification /health démarre le conteneur (min_containers=0) : compter ~40 s de froid.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODAL="${MODAL_BIN:-$HOME/.venv-modal/bin/modal}"
PROFILE="${MODAL_PROFILE:-baptiste-faisy}"
URL="${LIVE_HEALTH_URL:-https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run/health}"
TIMEOUT="${LIVE_HEALTH_TIMEOUT:-300}"

deploy=1
case "${1:-}" in
  --no-deploy) deploy=0 ;;
  -h|--help)   sed -n '2,9p' "$0"; exit 0 ;;
  "")          ;;
  *)           echo "option inconnue : $1 (voir --help)" >&2; exit 2 ;;
esac

if (( deploy )); then
  echo "== deploy sam3-eu-a100 (A100-40GB, eu / eu-west, batch_prompts=1, edge=512, timing, scaledown=120)"
  ( cd "$ROOT" && LIVE_APP_NAME=sam3-eu-a100 FAST_GPU=A100-40GB FAST_REGION=eu \
      FAST_ROUTING_REGION=eu-west FAST_BATCH_PROMPTS=1 FAST_EDGE=512 \
      FAST_TIMING=1 FAST_SCALEDOWN=120 \
      "$MODAL" deploy backend/live_app_fast.py --profile "$PROFILE" )
fi

echo "== vérification $URL"
body="$(curl -fsS --max-time "$TIMEOUT" "$URL")" || {
  echo "KO : /health injoignable ($URL)" >&2; exit 1; }

printf '%s' "$body" | python3 -c '
import json, sys
h = json.load(sys.stdin)
lev = h.get("levers") or {}
print("gpu    :", h.get("gpu"))
print("region :", h.get("region"))
print("model  :", h.get("model"))
print("levers :", json.dumps(lev, ensure_ascii=False, sort_keys=True))
if "fast" not in str(h.get("model", "")).lower():
    sys.exit("KO : model ne contient pas \"fast\" (mauvaise app déployée ?)")
if lev.get("batch_prompts") is not True:
    sys.exit("KO : levers.batch_prompts n est pas true (FAST_BATCH_PROMPTS=1 absent ?)")
print("OK : app rapide en place")
'
