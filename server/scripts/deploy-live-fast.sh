#!/usr/bin/env bash
# SEULE commande de déploiement du backend live (l'app du téléphone) — app sam3-eu-a100.
#
#   bash scripts/deploy-live-fast.sh              # déploie, attend la nouvelle version, vérifie
#   bash scripts/deploy-live-fast.sh --no-deploy  # vérifie seulement (sans réveiller si elle dort)
#
# Les leviers sont ici, et **seulement ici** : c'est ce qui les rend persistants.
#   FAST_CUDA_GRAPH=1    graphe CUDA de l'encodeur vision (45 ms → 1,4 ms mesurés)
#   FAST_SCALEDOWN=300   5 min de fenêtre avant extinction du conteneur
#   FAST_EDGE=512        grille du modèle (le serveur rééchantillonne à 518)
#   FAST_BATCH_PROMPTS=1 les N concepts en un forward (le lot est actif)
#
# Ne JAMAIS déployer backend/live_app.py (version lente, 205-241 ms).
#
# Piège appris le 26/10 : un `modal deploy` ne prend pas le trafic tant que l'ancien
# conteneur vit — la vérification lisait donc l'ancienne config et échouait à tort.
# Le script attend la nouvelle version, et à défaut arrête l'app (dernier recours).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODAL="${MODAL_BIN:-modal}"
URL="${LIVE_HEALTH_URL:-https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run/health}"
TIMEOUT="${LIVE_HEALTH_TIMEOUT:-300}"

APP_NAME="${LIVE_APP_NAME:-sam3-eu-a100}"
GPU="${FAST_GPU:-A100-40GB}"
REGION="${FAST_REGION:-eu}"
ROUTING="${FAST_ROUTING_REGION:-eu-west}"

# `${VAR-default}` et non `${VAR:-default}` : en CI la variable est définie **vide**
# exprès (pas de ~/.modal.toml), et `:-` y remettrait le profil local → erreur
# « No such option: -profile » (vu le 26/10 sur le runner GitHub).
PROFILE="${MODAL_PROFILE-baptiste-faisy}"
profile_arg=()
[ -n "$PROFILE" ] && profile_arg=(--profile "$PROFILE")

deploy=1
case "${1:-}" in
  --no-deploy) deploy=0 ;;
  -h|--help)   sed -n '2,20p' "$0"; exit 0 ;;
  "") ;;
  *) echo "option inconnue : $1 (voir --help)" >&2; exit 2 ;;
esac

deploy_once() {
  ( cd "$ROOT" && LIVE_APP_NAME="$APP_NAME" FAST_GPU="$GPU" FAST_REGION="$REGION" \
      FAST_ROUTING_REGION="$ROUTING" FAST_BATCH_PROMPTS=1 FAST_EDGE=512 \
      FAST_CUDA_GRAPH=1 FAST_SCALEDOWN=300 \
      "$MODAL" deploy backend/live_fast/live_app_fast.py "${profile_arg[@]}" )
}

# « ok » si /health montre l'app rapide AVEC ses leviers ; sinon la raison.
check_once() {
  local body
  body="$(timeout 60 curl -fsS --max-time 50 "$URL" 2>/dev/null)" || { echo "injoignable"; return 1; }
  printf '%s' "$body" | python3 -c '
import json, sys
h = json.load(sys.stdin)
lev, app = h.get("levers") or {}, h.get("applied") or {}
print("gpu    :", h.get("gpu"))
print("model  :", h.get("model"))
print("levers :", json.dumps(lev, ensure_ascii=False, sort_keys=True))
print("applied:", json.dumps(app, ensure_ascii=False, sort_keys=True))
missing = [k for k, v in {"batch_prompts": True, "cuda_graph": True, "edge": 512}.items()
           if lev.get(k) != v]
if "fast" not in str(h.get("model", "")).lower():
    sys.exit("model pas \"fast\"")
if missing:
    sys.exit("leviers absents : %s" % ",".join(missing))
if app.get("vision_accel") != "ok":
    sys.exit("vision_accel=%s" % app.get("vision_accel"))
'
}

# Attend que la **nouvelle** version serve (l'ancien conteneur peut vivre 20-300 s).
wait_for_new_version() { # essais, délai
  local tries="$1" delay="$2" i
  for i in $(seq 1 "$tries"); do
    sleep "$delay"
    check_once && return 0
  done
  return 1
}

if (( deploy )); then
  echo "== deploy $APP_NAME ($GPU, $REGION / $ROUTING, cuda_graph=1, scaledown=300, edge=512)"
  deploy_once || { echo "KO : le déploiement a échoué" >&2; exit 1; }
  echo "== attente de la nouvelle version"
  if wait_for_new_version 6 10; then
    echo "OK : app rapide en place, graphe vision actif"
    exit 0
  fi
  echo "== l'ancien conteneur sert encore : arrêt de l'app puis redéploiement"
  timeout 120 "$MODAL" app stop "$APP_NAME" -y "${profile_arg[@]}" >/dev/null 2>&1 || true
  deploy_once || { echo "KO : redéploiement impossible" >&2; exit 1; }
  if wait_for_new_version 12 10; then
    echo "OK : app rapide en place après arrêt (graphe vision actif)"
    exit 0
  fi
  echo "KO : la nouvelle version ne prend pas le trafic" >&2
  exit 1
fi

echo "== vérification $URL"
# Ne JAMAIS réveiller la carte pour vérifier : un /health sort le conteneur de veille
# (29 s) et empêche l'extinction que l'app demande en arrière-plan (POST /release).
if ! timeout 60 "$MODAL" container list "${profile_arg[@]}" --json 2>/dev/null \
     | APP_WANTED="$APP_NAME" python3 -c '
import json, os, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print("no"); raise SystemExit
print("yes" if any(c.get("app_name") == os.environ.get("APP_WANTED") for c in data) else "no")
' | grep -q yes; then
  echo "OK : carte au repos — rien à vérifier (aucun ping, elle reste éteinte)"
  exit 0
fi
check_once || { echo "KO" >&2; exit 1; }
echo "OK : app rapide en place, graphe vision actif"