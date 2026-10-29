#!/usr/bin/env bash
# Installe / met à jour le SDK Android en ligne de commande, sans root.
# Tout va dans $ANDROID_HOME (défaut : ~/Android/Sdk).
# Idempotent : relançable à volonté, ne retélécharge que ce qui manque.
#
#   ./server/scripts/install-android-sdk.sh            # installation standard
#   ./server/scripts/install-android-sdk.sh --minimal  # sans NDK/CMake (build JS seul)
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-env.sh
. "$HERE/lib-env.sh"

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
CMDLINE_BUILD="${CMDLINE_BUILD:-13114758}"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

BASE_PKGS=(
  "platform-tools"
  "platforms;android-35"
  "build-tools;35.0.0"
  # React Native 0.88 (Expo SDK 58) : compileSdk 37 / targetSdk 36 / buildTools 37.0.0
  "platforms;android-36"
  "platforms;android-37.0"
  "build-tools;36.0.0"
  "build-tools;37.0.0"
)
# React Native 0.76 / Expo SDK 52+ : NDK 27 + CMake 3.22.1
NATIVE_PKGS=(
  "ndk;27.1.12297006"
  "cmake;3.22.1"
)
EXTRA_PKGS=(
  "platforms;android-34"
  "build-tools;34.0.0"
)

# yes | sdkmanager : `yes` meurt par SIGPIPE, ce qui ferait échouer le pipefail.
# On désactive pipefail le temps du pipe ; le code de sortie de sdkmanager
# (dernier maillon) est alors celui du pipeline.
sdkm() {
  local rc
  set +o pipefail
  yes 2>/dev/null | "$SDKMANAGER" "$@"
  rc=$?
  set -o pipefail
  return $rc
}

log()  { printf '\033[1;34m[sdk]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[sdk]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[sdk]\033[0m %s\n' "$*" >&2; exit 1; }

# Arguments explicites : avant, tout argument différent de --minimal
# (--help, faute de frappe) lançait l'installation complète en silence.
case "${1:-}" in
  "") MINIMAL=0 ;;
  --minimal) MINIMAL=1 ;;
  -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
  *) echo "argument inconnu : $1 (standard, --minimal, --help)" >&2; exit 2 ;;
esac

# ---------------------------------------------------------------- prérequis
need_cmd() { command -v "$1" >/dev/null 2>&1 || die "commande manquante : $1"; }
need_cmd unzip
need_cmd curl

# Un JDK *complet* est requis : le JRE système n'a pas de javac (cf. lib-env.sh).
find_jdk || die "aucun JDK avec javac trouvé (Java 17+). Installe un JDK ou pose-le dans ~/.gradle/jdks"
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)"
[ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge 17 ] \
  || die "Java 17+ requis (détecté : ${JAVA_MAJOR:-inconnu} dans $JAVA_HOME)"
log "JDK : $JAVA_HOME (Java $JAVA_MAJOR)"

mkdir -p "$SDK_ROOT/cmdline-tools"

# ------------------------------------------------------- cmdline-tools latest
install_cmdline_tools() {
  local url="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_BUILD}_latest.zip"
  local tmp
  tmp="$(mktemp -d)"
  log "téléchargement cmdline-tools ($CMDLINE_BUILD)"
  curl -fL --retry 3 --connect-timeout 20 -o "$tmp/tools.zip" "$url" \
    || die "téléchargement impossible : $url"
  unzip -q "$tmp/tools.zip" -d "$tmp/x"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  mv "$tmp/x/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
  chmod +x "$SDK_ROOT/cmdline-tools/latest/bin/"* 2>/dev/null || true
}

if [ ! -x "$SDKMANAGER" ]; then
  install_cmdline_tools
else
  log "cmdline-tools déjà présents ($("$SDKMANAGER" --version 2>/dev/null | tail -1))"
fi
[ -x "$SDKMANAGER" ] || die "sdkmanager introuvable après installation"

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"

# --------------------------------------------------------------- licences
log "acceptation des licences (batch)"
sdkm --licenses >/dev/null || warn "certaines licences ont échoué"

# --------------------------------------------------------------- paquets
PKGS=("${BASE_PKGS[@]}")
if [ "$MINIMAL" = 0 ]; then PKGS+=("${NATIVE_PKGS[@]}"); fi
# Les extras sont optionnels : ne bloquent pas si indisponibles.
log "installation : ${PKGS[*]}"
sdkm --install "${PKGS[@]}" || die "échec d'installation des paquets de base"

if [ "$MINIMAL" = 0 ]; then
  log "installation (optionnelle) : ${EXTRA_PKGS[*]}"
  sdkm --install "${EXTRA_PKGS[@]}" \
    || warn "paquets optionnels non installés (sans gravité)"
fi

# --------------------------------------------------------------- vérif
log "paquets installés :"
"$SDKMANAGER" --list_installed 2>/dev/null | sed 's/^/[sdk]   /'

log "SDK prêt dans $SDK_ROOT"
cat <<EOF

Pour l'utiliser dans ce shell :
  export ANDROID_HOME="$SDK_ROOT"
  export ANDROID_SDK_ROOT="$SDK_ROOT"
  export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH"
EOF