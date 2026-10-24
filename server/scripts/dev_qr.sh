#!/usr/bin/env bash
# Lance le serveur de dev Expo (tunnel) pour tester l'app dans Expo Go.
# Usage : ./scripts/dev_qr.sh [--no-install] [-- <args expo>]
set -euo pipefail

# shellcheck source=lib-env.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib-env.sh"
APP_DIR="${APP_DIR:-$PROJECT_ROOT/app}"
NO_INSTALL=0
EXPO_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-install) NO_INSTALL=1; shift ;;
    --) shift; EXPO_ARGS+=("$@"); break ;;
    *) EXPO_ARGS+=("$1"); shift ;;
  esac
done

[[ -d "$APP_DIR" ]] || die "répertoire app introuvable : $APP_DIR"
cd "$APP_DIR"

log "Vérifications"
command -v node >/dev/null || die "node introuvable (installe Node.js ≥ 20)"
command -v npx  >/dev/null || die "npx introuvable (npm fourni avec Node.js ?)"
[[ -f package.json ]] || die "package.json absent dans $APP_DIR (l'app Expo est écrite par un autre agent)"
ok "node $(node --version), npx $(npx --version)"

if ! grep -q '"expo"' package.json; then
  die "package.json ne déclare pas expo — mauvais répertoire ?"
fi

if [[ ! -d node_modules ]]; then
  if [[ $NO_INSTALL -eq 1 ]]; then
    die "node_modules absent et --no-install demandé : lance 'npm install' dans app/"
  fi
  warn "node_modules absent : installation des dépendances"
  if [[ -f package-lock.json ]]; then
    timeout 900 npm ci || die "npm ci a échoué"
  else
    timeout 900 npm install || die "npm install a échoué"
  fi
  ok "dépendances installées"
else
  ok "node_modules présent"
fi

[[ -d node_modules/expo ]] || die "expo absent de node_modules : 'npm install' dans app/ puis relance"
[[ -d node_modules/@expo/ngrok ]] \
  || warn "@expo/ngrok absent : le tunnel peut demander une installation au premier lancement"

API_URL_FROM_CONFIG="$(sed -n 's/.*"apiUrl"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' app.json 2>/dev/null | head -n1)"
if [[ -n "${API_URL_FROM_CONFIG:-}" ]]; then
  ok "apiUrl par défaut (app.json) : $API_URL_FROM_CONFIG"
else
  warn "expo.extra.apiUrl vide dans app.json : renseigne l'URL Modal dans l'écran Réglages"
fi

log "Démarrage d'Expo (tunnel) — scanne le QR code avec Expo Go"
printf '\033[33m  Ctrl+C pour arrêter le serveur.\033[0m\n\n'
exec npx expo start --tunnel "${EXPO_ARGS[@]+"${EXPO_ARGS[@]}"}"