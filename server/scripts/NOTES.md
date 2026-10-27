# NOTES de build — backend legacy (server/)

## Réglages hors `scripts/` (à valider par le parent)

1. **`app/app.json`** — ajout de `expo.android.package = "com.baptiste.sam3vision"`
   et `versionCode = 1`. Sans cela, `expo prebuild` génère
   `com.anonymous.sam3scaffold` (dérivé du slug) et `adb shell monkey -p
   com.baptiste.sam3vision` ne trouverait rien. Aucun autre champ touché.

## Contraintes machine découvertes

2. **JDK** : `/usr/lib/jvm/java-21-openjdk-amd64` est un **JRE** (pas de `javac`).
   Premier `assembleRelease` → `Toolchain installation ... does not provide the
   required capabilities: [JAVA_COMPILER]`. Solution : utiliser le JDK complet
   `~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2` (Temurin 17.0.21). Fait
   automatiquement par `scripts/lib-env.sh` (`find_jdk`).

3. **SDK réellement requis** par React Native 0.88 / Expo SDK 58 (lu dans
   `node_modules/react-native/gradle/libs.versions.toml`) :
   `compileSdk 37`, `targetSdk 36`, `minSdk 24`, `buildTools 37.0.0`,
   `ndkVersion 27.1.12297006` → paquets `platforms;android-36`,
   `platforms;android-37.0`, `build-tools;36.0.0`, `build-tools;37.0.0`
   (en plus de android-35 / build-tools 35 demandés). Ajoutés au script
   d'installation.

4. **Gradle** : wrapper 9.4.1 (téléchargé par le wrapper), Kotlin 2.3.0.

## Blocage non résolu : installation sur le téléphone physique

5. Redmi `2312DRA50G` (adb `2c6852ac`, Android 15, MIUI) : `adb install -r`
   échoue en `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`, y
   compris après `settings put {global,secure} verifier_verify_adb_installs 0`
   et `global adb_install_need_confirm 0` (3 essais). MIUI exige le réglage
   manuel **Options développeur → « Installer via USB »** (et un compte Mi
   connecté) ; impossible à activer depuis l'hôte.
   **Contournement** : APK installé et lancé sur `emulator-5554`
   (`monkey -p com.baptiste.sam3vision 1` → `MainActivity` au premier plan,
   aucun crash dans logcat).

## Résultat

6. `assembleRelease` OK : `app/android/app/build/outputs/apk/release/app-release.apk`
   (88 Mo, 4 ABIs : arm64-v8a, armeabi-v7a, x86, x86_64).
   Signé avec le **keystore debug** (pas de keystore de prod fourni) : bon pour
   tester, à remplacer avant toute publication.