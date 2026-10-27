#!/usr/bin/env bash
# Construit un module Android sur le r630 (80 coeurs) puis rapatrie l'APK.
#
# Différence avec ~/.local/bin/build-serveur : les clés de local.properties
# (llm.api.key, tts.api.key, sam31.endpoint…) sont transmises au serveur, sinon
# BuildConfig sort avec des chaînes vides et l’app ne peut appeler personne.
#
#   tools/r630-build.sh [worktree] [tâche gradle]
#
set -euo pipefail

WT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
TASK="${2:-:app:assembleDebug}"
SRV="${BUILD_SERVER:-baptiste@100.124.18.48}"
JDK="${BUILD_JDK:-/home/baptiste/toolchains/jdk17}"
SDK="${BUILD_SDK:-/home/baptiste/android-sdk}"
NAME="$(basename "$WT")"
DEST="builds/$NAME"

echo "[r630-build] envoi de $NAME ($TASK)"
tar -C "$(dirname "$WT")" --exclude=.git --exclude=build --exclude=.gradle --exclude=.kotlin \
    -czf - "$NAME" | ssh "$SRV" "mkdir -p $DEST && tar -xzf - -C $DEST --strip-components=1"

# local.properties du PC (clés) + sdk.dir du serveur. Jamais commité.
{ printf 'sdk.dir=%s\n' "$SDK"
  [ -f "$WT/local.properties" ] && grep -vE '^sdk\.dir=' "$WT/local.properties" || true
} | ssh "$SRV" "cat > $DEST/local.properties"

echo "[r630-build] gradle $TASK"
ssh "$SRV" "cd $DEST && JAVA_HOME='$JDK' ANDROID_HOME='$SDK' ANDROID_SDK_ROOT='$SDK' \
    GRADLE_USER_HOME=/home/baptiste/.gradle ./gradlew --console=plain $TASK"

echo "[r630-build] APK produits :"
ssh "$SRV" "find $DEST -path '*outputs/apk*' -name '*.apk' -newermt '-30 minutes' 2>/dev/null" || true