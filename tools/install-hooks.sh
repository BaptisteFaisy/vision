#!/bin/sh
# install-hooks — branche le hook `pre-push` du dépôt dans le dossier de hooks
# global (~/.git-hooks, déjà utilisé par la garde d'identité git).
#
# Pourquoi un shim et pas `core.hooksPath` dans le dépôt : ce poste a déjà un
# `core.hooksPath` global (~/.git-hooks) qui porte la garde d'identité. Poser
# `core.hooksPath=.githooks` localement la désactiverait. Le shim délègue donc
# au script versionné du dépôt courant, qui s'auto-limite à BaptisteFaisy/vision
# et laisse passer tous les autres dépôts.
#
# Usage : tools/install-hooks.sh [--uninstall] [--verifier]
#   --uninstall  retire le shim (le script du dépôt reste)
#   --verifier   dit seulement si le shim est en place (code 0 = oui)
#
# Idempotent : relançable à volonté, ne duplique jamais le shim.

set -eu

HOOKS="${ZC_GIT_HOOKS_DIR:-$HOME/.git-hooks}"
SHIM="$HOOKS/pre-push"
MARQUE="# shim installé par tools/install-hooks.sh (dépôt vision)"

cas=${1:-}

if [ "$cas" = "--verifier" ]; then
  if [ -x "$SHIM" ] && grep -qF "$MARQUE" "$SHIM" 2>/dev/null; then
    echo "install-hooks : shim pre-push en place ($SHIM)"
    exit 0
  fi
  echo "install-hooks : shim pre-push ABSENT ($SHIM)"
  exit 1
fi

if [ "$cas" = "--uninstall" ]; then
  if [ -f "$SHIM" ] && grep -qF "$MARQUE" "$SHIM" 2>/dev/null; then
    rm -f "$SHIM"
    echo "install-hooks : shim retiré"
  else
    echo "install-hooks : aucun shim à retirer"
  fi
  exit 0
fi

mkdir -p "$HOOKS"

# Un pre-push voisin qui ne vient pas de nous : on le préserve en *.local et le
# shim l'appelle d'abord (chaînage, jamais d'écrasement silencieux).
if [ -f "$SHIM" ] && ! grep -qF "$MARQUE" "$SHIM" 2>/dev/null; then
  mv "$SHIM" "$HOOKS/pre-push.local"
  echo "install-hooks : pre-push existant conservé en pre-push.local (chaîné)"
fi

cat > "$SHIM" <<EOF
#!/bin/sh
$MARQUE
# Délègue au script versionné du dépôt courant ; ce script s'auto-limite au
# dépôt BaptisteFaisy/vision et gère lui-même les pushes forcés, master et les
# branches de file.
if [ -x "\$HOME/.git-hooks/pre-push.local" ]; then
  "\$HOME/.git-hooks/pre-push.local" || exit \$?
fi
racine=\$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
[ -n "\$racine" ] || exit 0
[ -x "\$racine/tools/hooks/pre-push" ] || exit 0
exec "\$racine/tools/hooks/pre-push"
EOF
chmod +x "$SHIM"

echo "install-hooks : shim installé dans $SHIM"
echo "install-hooks : actif pour tout dépôt qui porte tools/hooks/pre-push"
