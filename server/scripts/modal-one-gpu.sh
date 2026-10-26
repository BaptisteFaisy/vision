#!/usr/bin/env bash
# Garde-fou « un seul GPU » — enveloppe mince de `sam3-gpu guard`.
# Une seule implémentation vit dans ~/bin/sam3-gpu (voir ref/sam3-gpu.md).
#
#   bash scripts/modal-one-gpu.sh                 # garde l'app SAM 3.1 ($SAM3_APP)
#   KEEP=sam3-live-eu bash scripts/modal-one-gpu.sh
#   bash scripts/modal-one-gpu.sh --status        # affiche seulement, n'arrête rien
#
# Déploiement du direct : scripts/deploy-live-fast.sh (jamais backend/live_app.py).
#
# Défaut corrigé le 2026-10-24 : l'ancien défaut (KEEP=sam3-live-b200) arrêtait
# silencieusement l'app live du téléphone (sam3-eu-a100) — incident de 22:21.
set -euo pipefail

BIN="${SAM3_GPU_BIN:-$HOME/bin/sam3-gpu}"
[[ -x "$BIN" ]] || { echo "sam3-gpu introuvable : $BIN" >&2; exit 1; }

case "${1:-}" in
  ""|--guard) exec "$BIN" guard ;;
  --status)   exec "$BIN" status ;;
  -h|--help)  exec "$BIN" --help ;;
  *)          echo "option inconnue : $1 (voir --help)" >&2; exit 2 ;;
esac