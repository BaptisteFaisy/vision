#!/usr/bin/env bash
# Garde-fou « un seul GPU » : ne laisse vivante qu'une app Modal à la fois.
#
#   bash scripts/modal-one-gpu.sh                 # garde sam3-live-b200
#   KEEP=sam3-live-eu bash scripts/modal-one-gpu.sh
#   bash scripts/modal-one-gpu.sh --status        # n'arrête rien, affiche seulement
#
# Pourquoi : chaque conteneur GPU déployé coûte dès qu'il tourne (B200 6,25 $/h,
# H200 4,54 $/h, A100 2,10 $/h). Une app « déployée mais à l'arrêt » ne coûte rien ;
# c'est le conteneur actif qu'il faut surveiller.
set -euo pipefail

MODAL_BIN="${MODAL_BIN:-$HOME/.venv-modal/bin/modal}"
MODAL_PROFILE="${MODAL_PROFILE:-baptiste-faisy}"
KEEP="${KEEP:-sam3-live-b200}"
STATUS_ONLY=0
[[ "${1:-}" == "--status" ]] && STATUS_ONLY=1

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m  !!\033[0m %s\n' "$*"; }

list_apps() {
  timeout 90 "$MODAL_BIN" --profile "$MODAL_PROFILE" app list 2>/dev/null \
    | grep -oE 'ap-[A-Za-z0-9]+.*' | awk '{print $1}' | sort -u
}

count_containers() {
  timeout 60 "$MODAL_BIN" --profile "$MODAL_PROFILE" container list 2>/dev/null | grep -c 'ta-' || true
}

log "Garde-fou : une seule app autorisée = $KEEP"
containers=$(count_containers)
log "conteneurs actifs : $containers"

if [[ "$STATUS_ONLY" == "0" ]]; then
  # Arrête toutes les apps déployées sauf celle qu'on garde (idempotent : --yes).
  while read -r line; do
    [[ -z "$line" ]] && continue
    name=$(echo "$line" | awk '{print $1}')
    state=$(echo "$line" | awk '{print $2}')
    [[ "$name" == "None" ]] && continue
    [[ "$state" != "deployed" ]] && continue
    if [[ "$name" == "$KEEP" ]]; then
      log "conservée : $name"
      continue
    fi
    log "arrêt : $name"
    timeout 60 "$MODAL_BIN" --profile "$MODAL_PROFILE" app stop "$name" -y >/dev/null 2>&1 || warn "arrêt impossible : $name"
  done < <(timeout 90 "$MODAL_BIN" --profile "$MODAL_PROFILE" app list 2>/dev/null | grep -E 'deployed' || true)
fi

containers=$(count_containers)
if [[ "$containers" -gt 1 ]]; then
  warn "PLUSIEURS CONTENEURS ACTIFS ($containers) — vérifie à la main : modal container list"
  exit 1
fi
log "OK : $containers conteneur(s) actif(s) — coût GPU nul hors direct"