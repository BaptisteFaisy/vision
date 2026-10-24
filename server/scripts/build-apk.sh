#!/usr/bin/env bash
# Construit l'APK Android de sam3-vision-app à partir de zéro, de façon idempotente.
#
#   ./scripts/build-apk.sh              # release (défaut)
#   ./scripts/build-apk.sh debug        # debug, signée avec le keystore debug
#   VARIANT=debug ./scripts/build-apk.sh
#
# Variables : ANDROID_HOME (défaut ~/Android/Sdk), GRADLE_JVMARGS (défaut -Xmx4g)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT/legacy-expo-app"
HERE="$ROOT/scripts"
# shellcheck source=lib-env.sh
. "$HERE/lib-env.sh"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
VARIANT="${1:-${VARIANT:-release}}"
GRADLE_JVMARGS="${GRADLE_JVMARGS:--Xmx4g}"

case "$VARIANT" in
  release) TASK="assembleRelease"; APK_DIR="release" ;;
  debug)   TASK="assembleDebug";   APK_DIR="debug" ;;
  *) echo "variante inconnue : $VARIANT (release|debug)" >&2; exit 2 ;;
esac

log()  { printf '\033[1;34m[apk]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[apk]\033[0m %s\n' "$*" >&2; exit 1; }

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

# Un JRE sans javac casse AGP : on exige un JDK complet.
find_jdk || die "aucun JDK avec javac trouvé (Java 17+ requis)"

# ------------------------------------------------------------- 1. SDK
[ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ] \
  || die "SDK absent : lance d'abord scripts/install-android-sdk.sh"
if ! "$SDK/cmdline-tools/latest/bin/sdkmanager" --list_installed 2>/dev/null \
     | grep -q "platforms;android-37"; then
  log "paquets SDK manquants → scripts/install-android-sdk.sh"
  "$ROOT/scripts/install-android-sdk.sh"
fi
log "SDK : $SDK"

# ------------------------------------------------------------- 2. deps JS
cd "$APP_DIR"
[ -f package.json ] || die "legacy-expo-app/package.json introuvable"
if [ ! -d node_modules ]; then
  log "npm install"
  npm install
fi

# ------------------------------------------------------------- 3. natif
# prebuild est rejoué si android/ manque OU si app.json a changé depuis
# (sinon un manifest/permissions périmés passeraient inaperçus).
MANIFEST="android/app/src/main/AndroidManifest.xml"
if [ ! -x android/gradlew ] || [ "app.json" -nt "$MANIFEST" ]; then
  log "expo prebuild"
  npx expo prebuild --platform android --no-install
fi

# ------------------------------------------------------------- 4. gradle
cd android
log "gradle $TASK (JAVA_HOME=$JAVA_HOME)"
./gradlew "$TASK" -x lint --no-daemon \
  "-Dorg.gradle.jvmargs=$GRADLE_JVMARGS" \
  "-Dorg.gradle.java.installations.paths=$JAVA_HOME" \
  -Dorg.gradle.java.installations.auto-download=false

# ------------------------------------------------------------- 5. résultat
APK="$(find app/build/outputs/apk -name '*.apk' -path "*$APK_DIR*" -printf '%T@ %p\n' 2>/dev/null \
      | sort -n | tail -1 | cut -d' ' -f2-)"
[ -n "${APK:-}" ] || die "aucun APK dans app/build/outputs/apk/$APK_DIR"
log "APK : $APP_DIR/android/$APK"
ls -lh "$APK"
printf '\n%s\n' "Installation sur l'appareil connecté :"
printf '  adb install -r %s/android/%s\n' "$APP_DIR" "$APK"