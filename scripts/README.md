# scripts/ — inventaire, secrets, hygiène

Le dépôt est **public** : un script qui recopie une valeur d'environnement dans un log,
ou qui fait un `rm -rf` sur une variable vide, est un incident. Cette page décrit ce que
contient `scripts/`, ce que chaque outil lit, écrit, et les règles communes.

## Inventaire

| Script | Rôle | Lit (env / entrées) | Écrit (effet visible) | Secrets | Dépendances vérifiées |
|---|---|---|---|---|---|
| `scripts/build.sh` | `assembleDebug --offline` puis contrôle que l'APK existe vraiment ; timeout 600 s par défaut | `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_TIMEOUT` | `app/build/outputs/apk/debug/app-debug.apk` (via Gradle), cache `~/.gradle` ; stdout : `JAVA_HOME`, `ANDROID_HOME`, chemin + taille de l'APK | aucun | `bash`, `timeout` (coreutils), JDK avec `bin/javac`, `./gradlew` |
| `scripts/install-emulator.sh` | `install -r` sur un émulateur en ligne, `pm grant CAMERA`, `am start`, capture d'écran rapatriée | `EMULATOR_SERIAL`, `SKIP_BUILD`, `APK`, `ADB`, `TMPDIR`, `ANDROID_HOME` (repli pour trouver `adb`) | `$TMPDIR/samvision-emu.XXXXXX/samvision_screen_<serial>.png` (**conservée** : c'est le livrable) ; `/sdcard/samvision_install_<pid>.png` (supprimé par `trap`) | aucun | `adb` (platform-tools), `timeout`, `awk` |
| `scripts/verify-tap-voix.sh` | recette manuelle sur émulateur : tap = vidéo, appui long 4 s = voix ; affiche l'écran, le vibreur et le nombre de lignes de crash | `EMULATOR_SERIAL`, `APK`, `ADB` | stdout seulement ; XML `uiautomator` dans `$TMP_WORK` (supprimé par `trap`), `/sdcard/isee_ui_<pid>.xml` (supprimé) | aucun — le logcat est **compté**, jamais recopié | `adb`, `timeout`, `grep`/`sed`/`awk`/`cut`/`tr` |
| `scripts/install-phone-latest.sh` | garde le téléphone de test sur le `master` de GitHub : compare le `versionName` installé au SHA distant, reconstruit dans un miroir dédié, installe ; `--check` ne touche à rien, `--lock` / `--unlock` figent et libèrent le téléphone (verrou manuel, sans durée) | `VISION_MIRROR`, `VISION_PHONE_STATE`, `PHONE_BIN`, `PHONE_SERIAL`, `VISION_BUILD_TIMEOUT`, `VISION_KEYS` | `~/.vision-phone/state.env`, `~/.vision-phone/{build,install-phone-latest}.log`, miroir `~/vision-phone/master` ; `adb install -r -d` sur le téléphone (sous le verrou `~/bin/phone`) | aucun | `git`, `bash`, `timeout`, `~/bin/phone`, `adb`, JDK + SDK pour le build |
| `scripts/lib/common.sh` | socle sourcé : `ROOT`, `die`, `warn`, `require_cmd`, `require_uint`, `std_args`, `safe_rm_rf`, `make_tmpdir`, `resolve_adb` | — | rien | aucun | — |
| `scripts/test_scripts.sh` | les tests de cette page, en une seule commande | — | temporaires dans `$TMPDIR/test-scripts.XXXXXX` (supprimés à la sortie) | aucun | `bash`, `grep`, `sed`, `awk`, `wc` |
| `scripts/gen_mask_golden.py` | fixtures dorées des masques (`bits`/`M1`) + IoU + arbitrage de variante ; `--write` écrit les deux artefacts, `--check` les compare (porte anti-dérive), `--selftest` réencode six vecteurs réels du dépôt à l'octet près | rien (aucun argument, aucun fichier d'entrée) | `core/src/test/java/dev/sam3vision/core/mask/MaskGoldenData.kt` (fixtures générées) et `docs/MESURES-u11c.txt` | aucun | `python3` (bibliothèque standard seule) |

Aucun script n'affiche ni ne copie `local.properties` (les clés y vivent, le fichier est
gitignoré). Une seule exception, en lecture par Gradle seulement : `install-phone-latest.sh`
**lie** le fichier de clés de la machine (`VISION_KEYS`, défaut
`~/.config/sam-vision/build-keys.properties`) dans son miroir, sinon l'APK installé partirait
sans clé et la dictée Meta n'aurait pas de moteur. Aucun script n'expose de clé d'API, de token ni de variable d'environnement
sensible : les seules variables affichées sont des **chemins** (`JAVA_HOME`, `ANDROID_HOME`).

## Règles appliquées (et testées)

- `set -euo pipefail` en tête de chaque script exécutable.
- Toute variable est citée (`"$VAR"`), y compris dans `timeout`, `adb -s` et les chemins.
- Une seule suppression récursive dans tout `scripts/` : `safe_rm_rf` (`lib/common.sh`), qui
  **refuse** un chemin vide, un chemin contenant `..`, et tout chemin hors de `${TMPDIR:-/tmp}`.
- Les temporaires passent par `mktemp` (jamais `/tmp/nom-fixe`, qui ouvre la porte à un
  pré-positionnement de lien symbolique) et sont nettoyés par un `trap ... EXIT`.
- Aucun `cd` sans vérification ; la racine du dépôt est déduite de `BASH_SOURCE`
  (`scripts/lib/../..`), jamais d'un chemin absolu de machine.
- Aucun `eval` : les arguments inconnus sortent en code 2 avec un message.
- Chaque dépendance externe est **déclarée** : `require_cmd` ou `resolve_adb` disent quoi
  installer (`coreutils`, `platform-tools`) au lieu d'échouer sur un `command not found`.
- `--help` : usage sur stdout, code 0. Argument inconnu : message sur stderr, code 2.

La capture de `install-emulator.sh` n'est **pas** supprimée à la sortie : c'est le résultat
attendu du script, affiché en dernière ligne. Son dossier est unique (`mktemp -d`), donc deux
exécutions parallèles ne se marchent pas dessus.

## Lancer les tests

```bash
bash scripts/test_scripts.sh      # 75 assertions, code 0 si tout passe
python3 scripts/gen_mask_golden.py --selftest   # 11 assertions sur l'encodeur de masques
python3 scripts/gen_mask_golden.py --check      # fixtures dorées <-> générateur
```

Les tests n'ouvrent ni Gradle ni un appareil réel : `adb` est remplacé par un faux binaire
dans un dossier temporaire (émulateur en ligne, hors ligne ou capture factice au choix).
Ils couvrent : syntaxe `bash -n`, présence de `set -euo pipefail`, `--help` (code 0) et
argument inconnu (code 2) sur les trois outils, gardes `ANDROID_HOME` / `GRADLE_TIMEOUT` / JDK
de `build.sh`, APK manquant et émulateur hors ligne de `install-emulator.sh`, `ADB` non
exécutable, APK manquant de `verify-tap-voix.sh`, les refus de `safe_rm_rf`, et des garde-fous
statiques (pas d'`eval`, pas de `/tmp` à nom fixe, pas de `/home/...` en dur, jamais
d'affichage de `local.properties`, un seul `rm -rf` — celui de `safe_rm_rf`), plus la limite
des 500 lignes.