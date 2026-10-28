#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# valhalla-funnel.sh — exposition publique du routeur Valhalla du r630
# (téléphone → /route) via Tailscale Funnel. Aucun proxy à écrire, aucun GPU
# touché, rien à installer sur le téléphone (HTTPS ordinaire).
#
# Usage : scripts/valhalla-funnel.sh <commande>
#   up            expose le port Valhalla sur Internet (`funnel --bg`)
#   status        config funnel + santé locale de Valhalla (lecture seule)
#   check <url>   santé de l'URL publique DEPUIS CE POSTE (pas de Tailscale)
#   ensure <url>  keepalive : check, sinon up (pour cron — voir la doc)
#   down          ROLLBACK : coupe l'exposition publique (`funnel reset`)
#
# Variables : R630_HOST (défaut baptiste@100.124.18.48), VALHALLA_PORT (8002),
# SSH_BIN (ssh — à surcharger pour les tests). Doc : docs/VOICE_NAV_PROXY.md.
# ---------------------------------------------------------------------------
set -euo pipefail

R630_HOST="${R630_HOST:-baptiste@100.124.18.48}"
VALHALLA_PORT="${VALHALLA_PORT:-8002}"
SSH_BIN="${SSH_BIN:-ssh}"

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \?//'
    printf '\nUsage: %s {up|status|check <url>|ensure <url>|down}\n' "$(basename -- "$0")"
}

die() {
    printf 'ERREUR : %s\n' "$1" >&2
    exit "${2:-1}"
}

on_r630() {
    "$SSH_BIN" -o BatchMode=yes -o ConnectTimeout=10 "$R630_HOST" "$1"
}

cmd_up() {
    on_r630 "tailscale funnel --bg ${VALHALLA_PORT}" || die "funnel refusé (admin tailnet ? voir la doc)"
    printf 'OK : port %s exposé. URL publique :\n' "$VALHALLA_PORT"
    on_r630 "tailscale funnel status"
}

cmd_status() {
    printf '== funnel ==\n'
    on_r630 "tailscale funnel status" || die "lecture funnel impossible"
    printf '== valhalla local ==\n'
    on_r630 "curl -s -m 8 http://127.0.0.1:${VALHALLA_PORT}/status" || die "Valhalla ne répond pas en local"
    printf '\n'
}

cmd_check() {
    [[ $# -ge 1 ]] || die "il manque l'URL publique : check <url>" 2
    local code
    code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$1/status")" || code="000"
    if [[ "$code" == 2* ]]; then
        printf 'OK : %s/status répond %s\n' "$1" "$code"
    else
        die "URL publique injoignable ($1/status → $code)"
    fi
}

cmd_ensure() {
    [[ $# -ge 1 ]] || die "il manque l'URL publique : ensure <url>" 2
    if cmd_check "$1" >/dev/null 2>&1; then
        printf 'OK : tunnel vivant (%s)\n' "$1"
    else
        printf 'Tunnel mort, ré-exposition…\n' >&2
        cmd_up
    fi
}

cmd_down() {
    on_r630 "tailscale funnel reset" || die "reset funnel impossible"
    printf 'OK : exposition publique coupée.\n'
}

case "${1:-}" in
    -h | --help) usage; exit 0 ;;
    up) shift; cmd_up "$@" ;;
    status) shift; cmd_status "$@" ;;
    check) shift; cmd_check "$@" ;;
    ensure) shift; cmd_ensure "$@" ;;
    down) shift; cmd_down "$@" ;;
    *) printf 'argument inconnu : %s\n' "${1:-(vide)}" >&2; usage >&2; exit 2 ;;
esac
