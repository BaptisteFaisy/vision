#!/usr/bin/env bash
# SEULE commande de déploiement du backend live (l'app du téléphone) — app sam3-eu-a100.
#
#   bash server/scripts/deploy-live-fast.sh              # déploie, attend la nouvelle version, vérifie
#   bash server/scripts/deploy-live-fast.sh --no-deploy  # vérifie seulement (sans réveiller si elle dort)
#
# Les leviers sont ici, et **seulement ici** : c'est ce qui les rend persistants.
#   FAST_CUDA_GRAPH=1    graphe CUDA de l'encodeur vision (45 ms → 1,4 ms mesurés)
#   FAST_DETR_GRAPH      ÉTEINT : le replay a crashé un conteneur en prod (mort brutale
#                        sans traceback après un 1er replay à 0,3 ms, cause inconnue).
#                        Code + tests conservés ; réactiver après diagnostic sur carte
#                        dédiée (reproduction contrôlée impossible sur la prod).
#   FAST_ATTN=sdpa       attention fusionnée (eager matérialise la matrice, ~55 ms)
#   FAST_SCALEDOWN=60    60 s de fenêtre avant extinction du conteneur
#   FAST_EDGE=512        grille du modèle (le serveur rééchantillonne à 518)
#   FAST_BATCH_PROMPTS=1 les N concepts en un forward (le lot est actif)
#   FAST_SNAPSHOT=1      snapshot mémoire (réveil 2-7 s au lieu de 25-38 s)
#   FAST_MODEL_DIR=...   poids dans l'image (plus de re-téléchargement au réveil)
#   FAST_TIMING=1        timings_ms + Server-Timing (validation des < 10 s)
#
# Ne JAMAIS déployer backend/live_app.py (version lente, 205-241 ms).
#
# Piège appris le 26/10 : un `modal deploy` ne prend pas le trafic tant que l'ancien
# conteneur vit — la vérification lisait donc l'ancienne config et échouait à tort.
# Le script attend la nouvelle version, et à défaut arrête l'app (dernier recours).
#
# Après un déploiement réussi, le script appelle /warm : le premier boot crée le
# snapshot mémoire (25-38 s à froid), les réveils suivants restaurent (2-7 s).
# Le déploiement CI est conditionnel (`[deploy-gpu]` dans le message, voir
# `.github/workflows/deploy-live-fast.yml`) : chaque deploy invalide le snapshot.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODAL="${MODAL_BIN:-modal}"
URL="${LIVE_HEALTH_URL:-https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run/health}"
WARM_URL="${URL%/health}warm"  # réveil explicite, sans inférence (voir `/warm`)

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

# Garde anti-stale (27/10) : déployer depuis un arbre en retard sur origin/master
# écrase les leviers mergés entre-temps (vu : le snapshot effacé le soir même par
# un deploy manuel d'un arbre d'avant) ; un arbre sale déploie de l'inreproductible.
# CI exclue (checkout détaché : HEAD == push). Échappatoire : DEPLOY_ALLOW_STALE=1
# (jamais en CI, jamais sans raison écrite).
if (( deploy )) && [ -z "${DEPLOY_ALLOW_STALE:-}" ]; then
  branch="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)"
  base="$(git -C "$ROOT" rev-parse -q --verify origin/master 2>/dev/null || true)"
  if [ "$branch" != "HEAD" ] && [ -n "$base" ]; then
    if [ "$(git -C "$ROOT" rev-parse HEAD)" != "$base" ]; then
      echo "KO : arbre en retard ou divergent sur origin/master —" >&2
      echo "  git fetch origin && git rebase origin/master, puis relance." >&2
      exit 1
    fi
    if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
      echo "KO : arbre sale — committe avant de déployer (ou DEPLOY_ALLOW_STALE=1)." >&2
      exit 1
    fi
  fi
fi

deploy_once() {
  ( cd "$ROOT" && LIVE_APP_NAME="$APP_NAME" FAST_GPU="$GPU" FAST_REGION="$REGION" \
      FAST_ROUTING_REGION="$ROUTING" FAST_BATCH_PROMPTS=1 FAST_EDGE=512 \
      FAST_CUDA_GRAPH=1 FAST_ATTN=sdpa FAST_SCALEDOWN=60 \
      FAST_SNAPSHOT=1 FAST_MODEL_DIR=/opt/models/sam3.1 FAST_TIMING=1 \
      "$MODAL" deploy backend/live_fast/live_app_fast.py "${profile_arg[@]}" )
}

# « ok » si /health montre l'app rapide AVEC ses leviers ; sinon la raison.
check_once() {
  local body
  body="$(timeout 60 curl -fsS --max-time 50 "$URL" 2>/dev/null)" \
    || { echo "injoignable ou 5xx (fenêtre de mort ?)"; return 1; }
  printf '%s' "$body" | python3 -c '
import json, sys
h = json.load(sys.stdin)
lev, app = h.get("levers") or {}, h.get("applied") or {}
restored = h.get("restored")
print("gpu    :", h.get("gpu"))
print("model  :", h.get("model"))
print("depth  :", h.get("depth") or "INDISPONIBLE (repli gracieux)")
print("levers :", json.dumps(lev, ensure_ascii=False, sort_keys=True))
print("applied:", json.dumps(app, ensure_ascii=False, sort_keys=True))
print("boot   :", "restore" if restored is True else
      "complet" if restored is False else "inconnu (backend trop vieux ?)")
print("load   :", json.dumps(h.get("load_breakdown"), ensure_ascii=False, sort_keys=True))
missing = [k for k, v in {"batch_prompts": True, "cuda_graph": True, "edge": 512,
                           "snapshot": True, "detr_graph": False,
                           "attn": "sdpa"}.items()
           if lev.get(k) != v]
if "fast" not in str(h.get("model", "")).lower():
    sys.exit("model pas \"fast\"")
if "depth" not in h:
    sys.exit("champ depth absent (ancienne version ?)")
if not isinstance(restored, bool):
    sys.exit("champ restored absent (backend trop vieux ?)")
lb = h.get("load_breakdown")
if not isinstance(lb, dict) or any(k not in lb for k in
        ("age_conteneur_s", "poids_cpu_1er_demarrage_s", "gpu_s")):
    sys.exit("champ load_breakdown absent ou incomplet")
if missing:
    sys.exit("leviers absents : %s" % ",".join(missing))
if app.get("vision_accel") != "ok":
    sys.exit("vision_accel=%s" % app.get("vision_accel"))
if app.get("attn_impl") != "sdpa":
    sys.exit("attn_impl=%s (sdpa non appliqué)" % app.get("attn_impl"))
'
}

# Attend que la **nouvelle** version serve (l'ancien conteneur peut vivre 20-300 s).
wait_for_new_version() { # essais, délai
  local tries="$1" delay="$2" i out
  for i in $(seq 1 "$tries"); do
    sleep "$delay"
    # 500/injoignable = fenêtre de mort (ancien tué, nouveau en boot 18-32 s) : retry
    # transparent, PAS une preuve que l'ancien sert encore. Seul un /health lisible
    # mais non conforme (vieux code, leviers faux) prouve l'ancien conteneur.
    if out="$(check_once 2>&1)"; then
      printf '%s\n' "$out"
      return 0
    fi
    echo "  essai $i/$tries : nouvelle version pas encore en ligne (${out##*$'\n'})"
  done
  return 1
}

# Crée le snapshot mémoire dès le deploy (le conteneur est déjà chaud : réponse
# immédiate, sans inférence). Best-effort : /health reste le verdict, un /warm
# en échec n'invalide pas un deploy sain (avertissement, pas KO).
warmup_snapshot() {
  timeout 120 curl -fsS --max-time 110 "$WARM_URL" 2>/dev/null \
    | python3 -c 'import json, sys; h = json.load(sys.stdin); print("warm:", h.get("warm"), "| restore:", h.get("restored"), "| boot:", h.get("load_seconds"), "s")'
}

if (( deploy )); then
  echo "== deploy $APP_NAME ($GPU, $REGION / $ROUTING, cuda_graph=1, detr_graph=OFF, attn=sdpa, scaledown=60, edge=512)"
  deploy_once || { echo "KO : le déploiement a échoué" >&2; exit 1; }
  echo "== attente de la nouvelle version"
  if wait_for_new_version 6 10; then
    warmup_snapshot || echo "avertissement : /warm sans réponse (snapshot non prouvé)" >&2
    echo "OK : app rapide en place, graphe vision actif"
    exit 0
  fi
  echo "== nouvelle version toujours pas en ligne : arrêt de l'app puis redéploiement"
  timeout 120 "$MODAL" app stop "$APP_NAME" -y "${profile_arg[@]}" >/dev/null 2>&1 || true
  deploy_once || { echo "KO : redéploiement impossible" >&2; exit 1; }
  if wait_for_new_version 12 10; then
    warmup_snapshot || echo "avertissement : /warm sans réponse (snapshot non prouvé)" >&2
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