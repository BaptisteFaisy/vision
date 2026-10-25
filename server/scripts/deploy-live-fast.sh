#!/usr/bin/env bash
# SEULE commande de déploiement du backend live (l'app du téléphone) — app sam3-eu-a100.
#
#   bash scripts/deploy-live-fast.sh              # déploie puis vérifie /health
#   bash scripts/deploy-live-fast.sh --no-deploy  # vérifie seulement
#
# Les leviers sont ici, et **seulement ici** : c'est ce qui les rend persistants.
#   FAST_CUDA_GRAPH=1    graphe CUDA de l'encodeur vision (45 ms → 1,4 ms mesurés)
#   FAST_SCALEDOWN=300   5 min de fenêtre : plus de réveil de 25-50 s entre deux gestes
#   FAST_EDGE=512        grille du modèle (le serveur rééchantillonne à 518)
#   FAST_BATCH_PROMPTS=1 les N concepts en un forward (le lot est actif)
#
# Ne JAMAIS déployer backend/live_app.py (version lente, 205-241 ms).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODAL="${MODAL_BIN:-modal}"
PROFILE="${MODAL_PROFILE:-baptiste-faisy}"
URL="${LIVE_HEALTH_URL:-https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run/health}"
TIMEOUT="${LIVE_HEALTH_TIMEOUT:-300}"

APP_NAME="${LIVE_APP_NAME:-sam3-eu-a100}"
GPU="${FAST_GPU:-A100-40GB}"
REGION="${FAST_REGION:-eu}"
ROUTING="${FAST_ROUTING_REGION:-eu-west}"

deploy=1
case "${1:-}" in
  --no-deploy) deploy=0 ;;
  -h|--help)   sed -n '2,19p' "$0"; exit 0 ;;
  "") ;;
  *) echo "option inconnue : $1 (voir --help)" >&2; exit 2 ;;
esac

# `--profile` n'existe qu'avec un ~/.modal.toml : en CI l'auth vient des jetons.
profile_arg=()
[ -n "$PROFILE" ] && profile_arg=(--profile "$PROFILE")

if (( deploy )); then
  echo "== deploy $APP_NAME ($GPU, $REGION / $ROUTING, cuda_graph=1, scaledown=300, edge=512)"
  ( cd "$ROOT" && LIVE_APP_NAME="$APP_NAME" FAST_GPU="$GPU" FAST_REGION="$REGION" \
      FAST_ROUTING_REGION="$ROUTING" FAST_BATCH_PROMPTS=1 FAST_EDGE=512 \
      FAST_CUDA_GRAPH=1 FAST_SCALEDOWN=300 \
      "$MODAL" deploy backend/live_fast/live_app_fast.py "${profile_arg[@]}" )
fi

echo "== vérification $URL"
body="$(curl -fsS --max-time "$TIMEOUT" "$URL")" || {
  echo "KO : /health injoignable ($URL)" >&2; exit 1; }

printf '%s' "$body" | python3 -c '
import json, sys
h = json.load(sys.stdin)
lev = h.get("levers") or {}
app = h.get("applied") or {}
print("gpu    :", h.get("gpu"))
print("region :", h.get("region"))
print("model  :", h.get("model"))
print("levers :", json.dumps(lev, ensure_ascii=False, sort_keys=True))
print("applied:", json.dumps(app, ensure_ascii=False, sort_keys=True))
if "fast" not in str(h.get("model", "")).lower():
    sys.exit("KO : model ne contient pas \"fast\" (mauvaise app déployée ?)")
if lev.get("batch_prompts") is not True:
    sys.exit("KO : levers.batch_prompts n est pas true")
if lev.get("cuda_graph") is not True:
    sys.exit("KO : levers.cuda_graph n est pas true (graphe vision perdu ?)")
if app.get("vision_accel") != "ok":
    sys.exit("KO : vision_accel = %s" % app.get("vision_accel"))
print("OK : app rapide en place, graphe vision actif")
'