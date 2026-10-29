# scripts — tests, déploiement Modal, build Android

Un script = un rôle. `lib-env.sh` est un helper *sourcé* (pas exécutable seul) :
`PROJECT_ROOT`, `log/ok/warn/die` et le **chemin unique du secret** Modal (`sync_secret_from_env` : mktemp `chmod 600` → `modal secret create
--from-json (avec écrasement)`, jamais de clé en argument).

## Tests

| Script | Rôle | Exemple |
|---|---|---|
| `test_units.py` | tests unitaires backend sans GPU ni token (imagerie, masques PNG, prompts, app FastAPI pilotée en ASGI) ; sort ≠ 0 si échec | `python3 scripts/test_units.py` |
| `test_api.py` | test E2E du contrat contre le backend déployé (stdlib seule) ; groupes `health`, `segment`, `segment-prompts`, `analyze`, `ask`, `bad-image`, `contract-errors` ; sort ≠ 0 si échec | `python3 scripts/test_api.py --url https://…modal.run --only contract-errors` |

## Déploiement Modal

| Script | Rôle | Exemple |
|---|---|---|
| `deploy_backend.sh` | pousse le secret `sam3-llm` depuis l'environnement, déploie `backend/modal_app.py`, affiche l'URL et lance le smoke test `/health` ; refuse de tourner sans `LLM_API_KEY` | `LLM_BASE_URL=… LLM_API_KEY=… LLM_MODEL=… ./scripts/deploy_backend.sh` |
| `rotate_llm_secret.sh` | rotation du secret depuis un fichier de clé (`API_KEY=…`, `MODEL=…`, ou une ligne brute), puis redéploiement | `./scripts/rotate_llm_secret.sh /chemin/cle.txt` |

Options utiles : `deploy_backend.sh --skip-smoke` (pas de `/health`), `--help`, et
`DRY_RUN=1` pour les deux scripts (valide l'environnement et les fichiers **sans appeler
Modal** : ni secret, ni déploiement). `MODAL_BIN`, `MODAL_PROFILE`, `SECRET_NAME`,
`APP_NAME`, `API_URL` sont surchargeables (`lib-env.sh` donne les défauts).

## Direct et mesures

| Script | Rôle | Exemple |
|---|---|---|
| `deploy-live-fast.sh` | **seule** commande de déploiement du direct (app `sam3-eu-a100`, leviers `FAST_*`, attente de la nouvelle version, vérification `/health`) ; `--no-deploy` vérifie sans déployer | `bash scripts/deploy-live-fast.sh` |
| `modal-one-gpu.sh` | garde-fou « une seule GPU » (enveloppe de `sam3-gpu guard`) | `bash scripts/modal-one-gpu.sh --status` |
| `bench_live.py` / `bench_live_ws.py` / `bench_track.py` / `phone_bench.py` | bancs de mesure HTTP / WebSocket / tracker / téléphone (réveillent un GPU : jamais en routine) | `python3 scripts/bench_live_ws.py --url wss://… --image … --runs 6` |
| `test_live_ws.py` | contrat WS sans GPU ni réseau (24 vérifications) | `python3 scripts/test_live_ws.py` |
| `test_units.py` + `tests_units/` | tests unitaires sans GPU (latence, fusion, poids, plomberie) | `python3 scripts/test_units.py` |
| `probe_*.py`, `local_segment.py`, `baseten_sync.py` | sondes de mesure, segmentation locale, synchro Baseten | — |

## App Android

L'APK ne se construit plus ici : l'app est native Kotlin à la racine du dépôt
(`./gradlew :app:assembleDebug`, cf. `README.md` racine et `scripts/build.sh`). Les scripts
`build-apk.sh`, `dev_qr.sh` et `install-android-sdk.sh` servaient l'app Expo **retirée de
l'arbre le 29/10/2026** (historique git : `git log -- server/legacy-expo-app`).