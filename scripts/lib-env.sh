#!/usr/bin/env bash
# Helpers partagés par install-android-sdk.sh et build-apk.sh.
# À sourcer : . "$(dirname "$0")/lib-env.sh"
# Fournit : find_jdk (met JAVA_HOME), sdk_env (met ANDROID_HOME/PATH).

# Cherche un JDK *complet* (avec javac) : le JRE système de cette machine
# (/usr/lib/jvm/java-21-openjdk-amd64) n'a pas de compilateur et casse AGP.
find_jdk() {
  local cand
  # 1. JAVA_HOME déjà valide
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    return 0
  fi
  # 2. JDK provisionnés par Gradle (Adoptium), puis emplacements classiques
  for cand in "$HOME"/.gradle/jdks/eclipse_adoptium-*-amd64-linux*/ \
              "$HOME"/.gradle/jdks/*/ \
              /usr/lib/jvm/*/ \
              /opt/*/; do
    if [ -x "${cand}bin/javac" ]; then
      export JAVA_HOME="${cand%/}"
      export PATH="$JAVA_HOME/bin:$PATH"
      return 0
    fi
  done
  return 1
}

# Positionne ANDROID_HOME / ANDROID_SDK_ROOT / PATH pour le SDK utilisateur.
sdk_env() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
  export PATH="$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:$sdk/emulator:$PATH"
  printf '%s\n' "$sdk"
}