# server/ — backend Modal SAM 3.1 (dépôt vision)

> ⚠️ **L'app Android est native Kotlin (à la racine du dépôt : `app/`, `core/`).
> Ce dossier garde le backend Modal SAM 3.1 et l'ancienne app Expo (legacy).**
> Fermeture Modal, relance en une commande et preuve de l'état : [`docs/MODAL-CLOSED.md`](docs/MODAL-CLOSED.md).
>
> Backend déployé aujourd'hui : `backend/live_fast/` (app `sam3-eu-a100`, voir
> [`docs/LIVE-FAST.md`](docs/LIVE-FAST.md)) ; `modal_app.py` est l'ère `sam3-vision`
> (`/analyze`, `/ask`). Le reste de cette page décrit l'époque Expo/legacy.

Photo → segmentation SAM 3.1 sur Modal → description et questions/réponses par un LLM vision, dans une app Android (Expo / React Native).

L'app ne contient aucune clé API : elle appelle un backend FastAPI déployé sur Modal, qui détient le modèle de segmentation et la clé du LLM.
*Description héritée de l'app Expo : l'app Kotlin appelle le LLM (InferHub) depuis le téléphone et n'utilise le backend Modal que pour `/segment` — la clé côté serveur n'est donc plus nécessaire, voir [`docs/MODAL-CLOSED.md`](docs/MODAL-CLOSED.md).*

## Ce que fait l'app

1. **Camera** — prise de photo (ou import depuis la galerie en secours).
2. **Analyse** — envoi à `/analyze` : masques d'objets + description textuelle qui cite les objets segmentés.
3. **Résultat** — overlays de masques colorés (opacité ~0,45, activables), description, et chat pour poser des questions sur la photo (`/ask`, avec historique).
4. **Réglages** — URL du backend modifiable et persistée ; écran d'erreur réseau explicite (timeout 60 s) avec bouton *Réessayer*.

## Architecture en 3 blocs

```
┌─────────────────────────┐      HTTPS/JSON      ┌──────────────────────────────────┐
│  App Expo (Android)     │ ───────────────────► │  Modal — app `sam3-vision`       │
│  expo-camera            │  image base64        │  FastAPI (asgi_app, GPU A10G)    │
│  expo-image-picker      │ ◄─────────────────── │  ├─ SAM 3.1  → masques PNG b64   │
│  overlays + chat        │  masques + texte     │  └─ LLM vision (API OpenAI-like) │
└─────────────────────────┘                      └──────────────────────────────────┘
   legacy-expo-app/                                backend/  (secret `sam3-llm`)
```

- **App** : Expo SDK stable, TypeScript, client API unique (`legacy-expo-app/src/api.ts`).
- **Segmentation** : `unsloth/sam3.1` (miroir non gated de `facebook/sam3.1`), chargé via `transformers` dans `@modal.enter()`, masques encodés en PNG base64.
- **LLM** : endpoint compatible OpenAI (`LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`), vision obligatoire, injecté par le secret Modal `sam3-llm`.

Détail du flux et des routes : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Prérequis

| Outil | Version / note |
|---|---|
| Python | ≥ 3.11 (client de test : stdlib uniquement) |
| Modal CLI | venv dédié `~/.venv-modal`, profil `baptiste-faisy` |
| Node.js | ≥ 20 (Expo SDK stable) |
| Android | Expo Go (dev) ou Android Studio / JDK 17 (build APK) |
| Compte Modal | avec jeton d'accès et GPU A10G autorisé |
| Clé LLM | endpoint compatible OpenAI **avec vision** |

## Déploiement backend

```bash
export LLM_BASE_URL="https://openrouter.ai/api/v1"
export LLM_API_KEY="<ta clé>"          # jamais commitée
export LLM_MODEL="<modèle vision>"

./scripts/deploy_backend.sh
```

Le script est idempotent : il vérifie le venv Modal, crée/actualise le secret `sam3-llm`
(`modal secret create (avec écrasement) --from-json`, valeurs lues dans l'environnement dans un fichier
`chmod 600` et jamais affichées — la clé ne passe **jamais** en argument de commande),
déploie `backend/modal_app.py`, affiche l'URL `https://…modal.run` et lance le test `/health`.

Options : `--skip-smoke`, `--help`, et `MODAL_PROFILE` / `MODAL_BIN` / `SECRET_NAME` /
`APP_NAME` surchargeables. `DRY_RUN=1 ./scripts/deploy_backend.sh` valide l'environnement et
les fichiers **sans appeler Modal** ; sans `LLM_API_KEY` le script refuse de tourner.
Pour changer seulement la clé : `./scripts/rotate_llm_secret.sh /chemin/cle.txt`
(rotation depuis un fichier, puis redéploiement).

## Test

```bash
# tests unitaires backend, sans GPU ni token Modal (stdlib + PIL + numpy + fastapi)
python3 scripts/test_units.py

# suite E2E complète (health, segment ± prompts, analyze, ask, erreur 400)
python3 scripts/test_api.py https://xxxx.modal.run

# ou via l'environnement
API_URL=https://xxxx.modal.run python3 scripts/test_api.py --timeout 90

# image imposée et test isolé
python3 scripts/test_api.py --url … --image photo.jpg --only health

# contrat des corps invalides : 7 cas → 400, sans réémission du payload
python3 scripts/test_api.py --url … --only contract-errors

# plusieurs groupes d'un coup
python3 scripts/test_api.py --url … --only segment,segment-prompts,bad-image

# tests du client Expo (parsing, api.ts contre un serveur local, masques) — sans npm
node legacy-expo-app/tools/test-client.mjs
```

Sans `--image`, `test_api.py` télécharge un échantillon public ; à défaut il génère une scène
synthétique. Code de sortie ≠ 0 dès qu'un test échoue (utilisable en CI). `--only analyze,ask`
nécessite une vraie clé LLM dans le secret `sam3-llm` (tant que la clé est un placeholder, ces
deux routes renvoient 500).

## Dev Expo Go

```bash
./scripts/dev_qr.sh            # npx expo start --tunnel depuis legacy-expo-app/
```

Vérifie `node_modules` (installe si absent), puis affiche un QR code à scanner avec Expo Go.
Renseigne ensuite l'URL Modal dans l'écran **Réglages** de l'app.

## Build APK

```bash
./scripts/build-apk.sh debug     # APK debug (keystore debug, installable direct)
./scripts/build-apk.sh           # release (défaut, keystore de release requis)
```

Le script vérifie le SDK Android (`ANDROID_HOME`, défaut `~/Android/Sdk`), installe les
dépendances JS, lance `expo prebuild` puis Gradle (`ABI`, `GRADLE_JVMARGS` surchargeables).

APK produite : `legacy-expo-app/android/app/build/outputs/apk/{debug,release}/`.
Équivalent manuel : `cd legacy-expo-app && npx expo prebuild -p android && cd android && ./gradlew assembleDebug`.

## Coûts indicatifs (GPU)

| Poste | Ordre de grandeur |
|---|---|
| A10G sur Modal | ~1,10 $/h de calcul facturé à la seconde |
| Cold start (chargement SAM 3.1) | **~36 s mesurés une seule fois** (35,8 s pour `/health`, puis 5,7 s pour le `/segment` suivant) ; le second essai après 567 s d'inactivité n'a donné aucun cold start — non reproductible, chiffres détaillés dans [`docs/PERF.md`](docs/PERF.md) |
| Requête `/segment` | ~1,6–2,0 s à chaud (médians mesurés sur 3 passes) → quelques centimes |
| Requête `/analyze` | segmentation + appel LLM : non mesuré tant que la clé LLM est absente (500 attendu) |
| LLM vision `/analyze`, `/ask` | ~0,001–0,01 $ par appel selon le modèle |
| App au repos | 0 $ si `scaledown_window` est court (60 s) ; si le conteneur ne descend pas, le GPU reste facturé |

À retenir : le coût dominant est le **temps de GPU allumé**, pas le nombre de requêtes.
Un `keep_warm` évite les cold starts mais facture en continu.

## Limites connues

- **Cold start** : ~36 s mesurés une seule fois après 450 s d'inactivité (`scaledown_window=300`) ; le second essai après 567 s n'en a produit aucun — non reproductible, à surveiller en console Modal (détail : `docs/PERF.md`).
- **Liste d'objets coupée à 24** : `MAX_OBJECTS = 24` dans `backend/segmentation.py` trie par score décroissant puis ne garde que les 24 premiers, **sans champ d'avertissement** dans la réponse (voir `docs/SPEC.md`).
- **Prompts libres** : la qualité dépend des concepts demandés ; sans prompt, le serveur choisit une liste générique et peut manquer de petits objets.
- **Masques PNG base64** : réponse lourde (quelques centaines de Ko à plusieurs Mo) → Wi-Fi conseillé, timeout client 60 s.
- **Sans état** : l'historique du chat est renvoyé par le client à chaque `/ask`, rien n'est stocké côté serveur.
- **Android uniquement**, pas d'écran iOS prévu ; Expo Go ne teste pas les modules natifs custom (utiliser un build de dev).
- **Pas d'authentification** sur le backend Modal : toute personne ayant l'URL peut consommer du GPU — à garder privée.
- SAM 3.1 est un modèle de segmentation *promptable*, pas un détecteur exhaustif : les scores faibles doivent être filtrés côté client.
