#!/usr/bin/env bash
# Build offline du debug APK. Idempotent : relançable tel quel.
# Env : JAVA_HOME / ANDROID_HOME surchargables ; GRADLE_TIMEOUT (défaut 600 s).
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib/common.sh" ||
  { printf 'ERREUR : scripts/lib/common.sh introuvable\n' >&2; exit 1; }

read -r -d '' USAGE <<'EOF' || true
Usage : build.sh [--help]

Construit l'APK debug hors ligne (assembleDebug --offline) et vérifie que
app/build/outputs/apk/debug/app-debug.apk existe vraiment à la fin.

Variables d'environnement :
  JAVA_HOME       JDK à utiliser ; il doit fournir bin/javac (un JRE seul ne suffit pas).
                  À défaut : Temurin 17 du cache Gradle, autre JDK du cache, JBR d'Android Studio.
  ANDROID_HOME    SDK Android (défaut : $HOME/Android/Sdk)
  GRADLE_TIMEOUT  durée maximale du build en secondes (défaut : 600)

Aucune valeur secrète n'est lue ni affichée par ce script.
EOF

# select_jdk : exporte JAVA_HOME vers un JDK qui fournit réellement un compilateur.
# Ordre : JAVA_HOME déjà valide, Temurin 17 du cache Gradle, autre JDK du cache,
# puis JBR d'Android Studio. Aucun chemin absolu de machine en dur.
select_jdk() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
    return 0
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    warn "JAVA_HOME='$JAVA_HOME' ne fournit pas bin/javac : recherche d'un autre JDK."
  fi
  local candidates=() jdk
  candidates+=("$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2")
  for jdk in "$HOME"/.gradle/jdks/*/bin/javac; do
    [[ -x "$jdk" ]] && candidates+=("$(dirname -- "$(dirname -- "$jdk")")")
  done
  candidates+=("$HOME/Android/Studio/jbr")
  for jdk in "${candidates[@]}"; do
    if [[ -x "${jdk}/bin/javac" ]]; then
      export JAVA_HOME="$jdk"
      return 0
    fi
  done
  return 1
}

# ensure_gradlew : gradlew présent et exécutable, sinon on le rend exécutable.
ensure_gradlew() {
  [[ -f "${ROOT}/gradlew" ]] || die "gradlew absent de $ROOT"
  if [[ ! -x "${ROOT}/gradlew" ]]; then
    chmod +x "${ROOT}/gradlew" || die "gradlew n'est pas exécutable et chmod a échoué"
  fi
}

main() {
  std_args "build.sh" "$USAGE" "$@"

  select_jdk || die "aucun JDK avec bin/javac trouvé (installez un JDK 17+ ou exportez JAVA_HOME)."

  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
  [[ -d "$ANDROID_HOME" ]] ||
    die "ANDROID_HOME absent : $ANDROID_HOME (exportez ANDROID_HOME vers le SDK Android)."

  local gradle_timeout="${GRADLE_TIMEOUT:-600}"
  require_uint GRADLE_TIMEOUT "$gradle_timeout"
  require_cmd timeout "Installez coreutils (paquet 'coreutils')."

  cd -- "$ROOT" || die "impossible d'entrer dans $ROOT"
  ensure_gradlew

  printf 'JAVA_HOME=%s\nANDROID_HOME=%s\n' "$JAVA_HOME" "$ANDROID_HOME"
  timeout "$gradle_timeout" ./gradlew assembleDebug --offline

  local apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  [[ -f "$apk" ]] || die "assembleDebug terminé sans APK attendu : $apk"
  printf 'APK : %s (%s)\n' "$apk" "$(du -h "$apk" | cut -f1)"
}

main "$@"