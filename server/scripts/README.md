# scripts — build Android

```bash
./scripts/install-android-sdk.sh     # SDK dans ~/Android/Sdk (idempotent)
./scripts/build-apk.sh               # release → app/android/app/build/outputs/apk/release/app-release.apk
./scripts/build-apk.sh debug         # variante debug
adb install -r app/android/app/build/outputs/apk/release/app-release.apk
```
Prérequis : JDK 17+ **avec `javac`** (le JRE système n'en a pas ; `lib-env.sh` en trouve un dans `~/.gradle/jdks`).
Le script exporte `ANDROID_HOME=~/Android/Sdk`, lance `expo prebuild` si `android/` manque, puis `./gradlew assembleRelease -x lint`.
Signature : release signée avec le keystore *debug* (pas de keystore de prod) — suffisant pour tester, pas pour publier.