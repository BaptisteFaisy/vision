# scripts — tests, déploiement Modal, build Android

Un script = un rôle. `lib-env.sh` est un helper *sourcé* (pas exécutable seul) :
`PROJECT_ROOT`, `log/ok/warn/die`, `find_jdk` / `sdk_env` (Android) et le **chemin unique
du secret** Modal (`sync_secret_from_env` : mktemp `chmod 600` → `modal secret create
--from-json --force`, jamais de clé en argument).

## Tests

| Script | Rôle | Exemple |
|---|---|---|
| `test_units.py` | tests unitaires backend sans GPU ni token (imagerie, masques PNG, prompts, app FastAPI pilotée en ASGI) ; sort ≠ 0 si échec | `python3 scripts/test_units.py` |
| `test_api.py` | test E2E du contrat contre le backend déployé (stdlib seule) ; groupes `health`, `segment`, `segment-prompts`, `analyze`, `ask`, `bad-image`, `contract-errors` ; sort ≠ 0 si échec | `python3 scripts/test_api.py --url https://…modal.run --only contract-errors` |
| `../app/tools/test-client.mjs` | tests du client Expo hors RN : parsing des 4 réponses, `api.ts` contre un serveur HTTP local, colorisation de masque pixel à pixel | `node app/tools/test-client.mjs` |

## Déploiement Modal

| Script | Rôle | Exemple |
|---|---|---|
| `deploy_backend.sh` | pousse le secret `sam3-llm` depuis l'environnement, déploie `backend/modal_app.py`, affiche l'URL et lance le smoke test `/health` ; refuse de tourner sans `LLM_API_KEY` | `LLM_BASE_URL=… LLM_API_KEY=… LLM_MODEL=… ./scripts/deploy_backend.sh` |
| `rotate_llm_secret.sh` | rotation du secret depuis un fichier de clé (`API_KEY=…`, `MODEL=…`, ou une ligne brute), puis redéploiement | `./scripts/rotate_llm_secret.sh /chemin/cle.txt` |

Options utiles : `deploy_backend.sh --skip-smoke` (pas de `/health`), `--help`, et
`DRY_RUN=1` pour les deux scripts (valide l'environnement et les fichiers **sans appeler
Modal** : ni secret, ni déploiement). `MODAL_BIN`, `MODAL_PROFILE`, `SECRET_NAME`,
`APP_NAME`, `API_URL` sont surchargeables (`lib-env.sh` donne les défauts).

## App Android / Expo

| Script | Rôle | Exemple |
|---|---|---|
| `install-android-sdk.sh` | installe/met à jour le SDK dans `~/Android/Sdk` sans root (idempotent ; `--minimal` sans NDK) | `./scripts/install-android-sdk.sh` |
| `build-apk.sh` | `expo prebuild` si besoin puis Gradle ; APK dans `app/android/app/build/outputs/apk/{release,debug}/` | `./scripts/build-apk.sh debug` |
| `dev_qr.sh` | serveur Expo en tunnel pour tester dans Expo Go (installe `node_modules` si absent) | `./scripts/dev_qr.sh` |

```bash
./scripts/install-android-sdk.sh                 # une fois
./scripts/build-apk.sh                           # release → app-release.apk
./scripts/build-apk.sh debug                     # variante debug
adb install -r app/android/app/build/outputs/apk/release/app-release.apk
./scripts/dev_qr.sh                              # ou : Expo Go + QR code
```

Prérequis : JDK 17+ **avec `javac`** (le JRE système n'en a pas ; `lib-env.sh` en trouve un dans
`~/.gradle/jdks`). `build-apk.sh` exporte `ANDROID_HOME=~/Android/Sdk`, lance `expo prebuild` si
`android/` manque, puis `./gradlew assembleRelease -x lint`. Signature : release signée avec le
keystore *debug* (pas de keystore de prod) — suffisant pour tester, pas pour publier.